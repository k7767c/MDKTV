"""Browser catalog views over the current, authorized metadata snapshot."""
from collections import OrderedDict
import hashlib
import json

from aiohttp import web


class WebCatalog:
    KINDS = frozenset(('album', 'genre', 'language'))
    CACHE_BYTES = 4 * 1024 * 1024

    def __init__(self, bridge):
        self.b = bridge
        self.cache = OrderedDict()
        self.cache_bytes = 0
        self.epoch = None

    def context(self, req):
        """Opaque scope changes with the logged-in user/source, not every sync."""
        identity = req.get('fnos_identity') or {}
        namespace = self.b.catalog.queries.token.split(':', 1)[0]
        source_scopes = [self.b.sources.music.data.get('scope', ''),
                         self.b.sources.video.data.get('scope', ''), self.b.sources.quark.data.get('scope', '')]
        principal = identity.get('uid') or self.b.cfg.get('username', '')
        scope = hashlib.sha256(json.dumps([namespace, principal, source_scopes],
            ensure_ascii=False, separators=(',', ':')).encode()).hexdigest()
        return {'appId': 'feiniu-karaoke', 'displayName': '飞牛 K歌',
                'basePath': req.get('app_base_path', ''),
                'deployment': 'fnos' if self.b.cfg.get('gateway_socket') else 'standalone',
                'admin': self.b.is_admin_session(req), 'catalogScope': scope,
                'catalogToken': self.b.catalog.queries.token, 'catalogRevision': self.b.catalog.revision,
                'sourceSetupRequired': not any(source.data.get('token') for source in
                    (self.b.sources.music, self.b.sources.video)) and not self.b.sources.quark.authenticated and not bool(self.b.videos.roots),
                'features': {'independentPlayback': True, 'lyrics': True, 'pitchScore': True,
                             'microphoneRequiresDevicePermission': True, 'sourceManagement': True}}

    def response(self, req):
        kind = req.query.get('kind', '')
        if kind not in self.KINDS:
            raise web.HTTPBadRequest(text='请选择专辑、分类或语种')
        token = self.b.catalog.queries.token
        if self.epoch != token:
            self.cache.clear()
            self.cache_bytes = 0
            self.epoch = token
        groups = req.path.endswith('/groups')
        page = max(1, min(1000000, int(req.query.get('page', 1))))
        size = max(1, min(500, int(req.query.get('pageSize', 50))))
        q = req.query.get('q', '').casefold()
        value = req.query.get('value', '')
        if len(q) > 500 or len(value) > 1000:
            raise web.HTTPBadRequest(text='筛选条件过长')
        key = (groups, kind, page, size, q, value)
        etag = '"' + hashlib.sha256(json.dumps((token, key), ensure_ascii=False).encode()).hexdigest()[:32] + '"'
        headers = {'ETag': etag, 'Cache-Control': 'private, max-age=0, must-revalidate', 'Vary': 'Cookie'}
        tags = [tag.strip().removeprefix('W/') for tag in req.headers.get('If-None-Match', '').split(',')]
        if etag in tags or '*' in tags:
            return web.Response(status=304, headers=headers)
        body = self.cache.get(key)
        if body is None:
            # The field name comes exclusively from KINDS; all client values are
            # bound parameters. Invisible/old-account songs never enter a view.
            field = "trim(COALESCE(json_extract(s.data,'$." + kind + "'),''))"
            base = ' FROM songs s JOIN current_guids c ON c.guid=s.guid'
            if groups:
                rows = self.b.db.execute('SELECT ' + field + ' AS value,count(*) AS count,min(s.id) AS cover_song'
                    + base + ' WHERE ' + field + "<>'' GROUP BY value ORDER BY value COLLATE NOCASE")
                items = [{'value': row['value'], 'name': row['value'], 'count': row['count'],
                          'coverSongId': row['cover_song']} for row in rows]
                data = {'items': items, 'total': len(items)}
            else:
                where = ' WHERE ' + field + '=?'
                params = [value]
                if q:
                    where += ' AND s.id IN (SELECT song_id FROM catalog_search WHERE instr(title_artist,?)>0 OR instr(title_artist_initials,?)>0)'
                    params.extend((q, q))
                total = self.b.db.execute('SELECT count(*)' + base + where, params).fetchone()[0]
                rows = self.b.db.execute('SELECT s.id,s.data' + base + where + ' ORDER BY s.id LIMIT ? OFFSET ?',
                    (*params, size, (page - 1) * size))
                items = [dict(json.loads(row['data']), id=row['id'], song_id=row['id']) for row in rows]
                data = {'items': items, 'total': total, 'page': page, 'pageSize': size}
            data.update(catalogToken=token, catalogRevision=self.b.catalog.revision)
            body = json.dumps(data, ensure_ascii=False, separators=(',', ':')).encode()
            if len(body) <= self.CACHE_BYTES:
                while self.cache and (len(self.cache) >= 32 or self.cache_bytes + len(body) > self.CACHE_BYTES):
                    _, old = self.cache.popitem(last=False)
                    self.cache_bytes -= len(old)
                self.cache[key] = body
                self.cache_bytes += len(body)
        else:
            self.cache.move_to_end(key)
        return web.Response(body=body, content_type='application/json', charset='utf-8', headers=headers)
