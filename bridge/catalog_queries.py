"""Bounded catalog queries over a maintained, compact Unicode search projection."""
from collections import OrderedDict
from functools import lru_cache
import hashlib
import json
import uuid
from aiohttp import web
from pypinyin import Style, lazy_pinyin


@lru_cache(maxsize=8192)
def initials(value):
    return ''.join(lazy_pinyin(str(value or ''), style=Style.FIRST_LETTER)).casefold()


class CatalogQueries:
    CACHE_LIMIT = 32
    CACHE_BYTES = 4 * 1024 * 1024

    def __init__(self, db):
        self.db = db
        self.cache = OrderedDict()
        self.counts = OrderedDict()
        self.cache_bytes = 0
        self.epoch = None
        db.create_function('karaoke_casefold', 1, lambda value: str(value or '').casefold(), deterministic=True)
        db.create_function('karaoke_initials', 1, initials, deterministic=True)
        db.executescript('''
            CREATE TABLE IF NOT EXISTS catalog_query_meta(
                singleton INTEGER PRIMARY KEY CHECK(singleton=1), namespace TEXT NOT NULL, version INTEGER NOT NULL);
            CREATE TABLE IF NOT EXISTS catalog_search(
                song_id INTEGER PRIMARY KEY, guid TEXT NOT NULL, title_artist TEXT NOT NULL,
                artist TEXT NOT NULL, artist_fold TEXT NOT NULL, media_kind TEXT);
            CREATE INDEX IF NOT EXISTS catalog_search_kind_id ON catalog_search(media_kind,song_id);
            CREATE INDEX IF NOT EXISTS catalog_search_artist ON catalog_search(artist,song_id);
            CREATE INDEX IF NOT EXISTS catalog_search_guid ON catalog_search(guid);
        ''')
        db.execute('INSERT OR IGNORE INTO catalog_query_meta VALUES(1,?,0)', (uuid.uuid4().hex,))
        columns = {row[1] for row in db.execute('PRAGMA table_info(catalog_search)')}
        for field in ('title_artist_initials', 'artist_initials'):
            if field not in columns:
                db.execute('ALTER TABLE catalog_search ADD COLUMN ' + field + " TEXT NOT NULL DEFAULT ''")
        fields = 'song_id,guid,title_artist,artist,artist_fold,media_kind,title_artist_initials,artist_initials'
        values = '''new.id,new.guid,
            karaoke_casefold(COALESCE(json_extract(new.data,'$.title'),'') || COALESCE(json_extract(new.data,'$.artist'),'')),
            COALESCE(json_extract(new.data,'$.artist'),''),karaoke_casefold(json_extract(new.data,'$.artist')),
            json_extract(new.data,'$.media_kind'),
            karaoke_initials(COALESCE(json_extract(new.data,'$.title'),'') || COALESCE(json_extract(new.data,'$.artist'),'')),
            karaoke_initials(json_extract(new.data,'$.artist'))'''
        for event in ('INSERT', 'UPDATE'):
            db.execute(f'DROP TRIGGER IF EXISTS catalog_search_{event.lower()}')
            db.execute(f'''CREATE TRIGGER catalog_search_{event.lower()} AFTER {event} ON songs
                BEGIN
                    INSERT OR REPLACE INTO catalog_search({fields}) VALUES({values});
                    UPDATE catalog_query_meta SET version=version+1 WHERE singleton=1;
                END''')
        db.executescript('''
            CREATE TRIGGER IF NOT EXISTS catalog_search_delete AFTER DELETE ON songs BEGIN
                DELETE FROM catalog_search WHERE song_id=old.id;
                UPDATE catalog_query_meta SET version=version+1 WHERE singleton=1;
            END;
            CREATE TRIGGER IF NOT EXISTS catalog_current_insert AFTER INSERT ON current_guids BEGIN
                UPDATE catalog_query_meta SET version=version+1 WHERE singleton=1;
            END;
            CREATE TRIGGER IF NOT EXISTS catalog_current_delete AFTER DELETE ON current_guids BEGIN
                UPDATE catalog_query_meta SET version=version+1 WHERE singleton=1;
            END;
            CREATE TRIGGER IF NOT EXISTS catalog_current_update AFTER UPDATE ON current_guids BEGIN
                UPDATE catalog_query_meta SET version=version+1 WHERE singleton=1;
            END;
        ''')
        # Migration runs once at startup, outside the first mobile list request.
        db.execute('''INSERT OR REPLACE INTO catalog_search(''' + fields + ''')
            SELECT s.id,s.guid,karaoke_casefold(COALESCE(json_extract(s.data,'$.title'),'') || COALESCE(json_extract(s.data,'$.artist'),'')),
                COALESCE(json_extract(s.data,'$.artist'),''),karaoke_casefold(json_extract(s.data,'$.artist')),
                json_extract(s.data,'$.media_kind'),
                karaoke_initials(COALESCE(json_extract(s.data,'$.title'),'') || COALESCE(json_extract(s.data,'$.artist'),'')),
                karaoke_initials(json_extract(s.data,'$.artist')) FROM songs s
            WHERE NOT EXISTS(SELECT 1 FROM catalog_search x WHERE x.song_id=s.id)''')
        db.execute("UPDATE catalog_search SET title_artist_initials=karaoke_initials(title_artist),artist_initials=karaoke_initials(artist) WHERE title_artist_initials='' AND title_artist<>''")
        if db.execute("SELECT 1 FROM sqlite_master WHERE name='history'").fetchone():
            db.execute('CREATE INDEX IF NOT EXISTS catalog_history_song ON history(song_id)')
        db.commit()

    @property
    def token(self):
        row = self.db.execute('SELECT namespace,version FROM catalog_query_meta WHERE singleton=1').fetchone()
        return str(row[0])+':'+str(row[1])

    @staticmethod
    def decode(row):
        return dict(json.loads(row['data']), id=row['id'], song_id=row['id'])

    def _cache(self, key, body):
        if len(body) > self.CACHE_BYTES:
            return
        while self.cache and (len(self.cache) >= self.CACHE_LIMIT or self.cache_bytes+len(body) > self.CACHE_BYTES):
            _, old = self.cache.popitem(last=False)
            self.cache_bytes -= len(old)
        self.cache[key] = body
        self.cache_bytes += len(body)

    def response(self, request, revision):
        path = request.path
        token = self.token
        if self.epoch != token:
            self.cache.clear()
            self.counts.clear()
            self.cache_bytes = 0
            self.epoch = token
        q = request.query.get('q', '').casefold()
        artist = request.query.get('artist', '').casefold()
        kind = request.query.get('media_kind', '')
        size = max(1, min(500, int(request.query.get('pageSize', 50)))) if path == '/api/songs' else 0
        start = max(0, int(request.query.get('page', 1))-1)*size if size else 0
        if path == '/api/songs' and kind and kind not in ('audio', 'mv'):
            raise web.HTTPBadRequest(text='未知媒体类型')
        # Chart ordering also changes when a song finishes; list pages do not.
        change = self.db.total_changes if path == '/api/charts' else 0
        key = (path, q, artist, kind, size, start, change, revision)
        etag = '"'+hashlib.sha256(json.dumps((token,key), ensure_ascii=False).encode()).hexdigest()[:32]+'"'
        headers = {'ETag':etag, 'Cache-Control':'private, max-age=0, must-revalidate', 'Vary':'Cookie'}
        tags = [value.strip().removeprefix('W/') for value in request.headers.get('If-None-Match','').split(',')]
        if etag in tags or '*' in tags:
            return web.Response(status=304, headers=headers)
        cached = self.cache.get(key)
        if cached is not None:
            self.cache.move_to_end(key)
            return web.Response(body=cached, content_type='application/json', charset='utf-8', headers=headers)
        if path == '/api/artists':
            where = ' WHERE instr(x.artist_fold,?)>0 OR instr(x.artist_initials,?)>0' if q else ''
            data = [{'artist':row[0], 'initials':row[1]} for row in self.db.execute('''SELECT DISTINCT x.artist,x.artist_initials
                FROM catalog_search x JOIN current_guids c ON x.guid=c.guid''' + where + ' ORDER BY x.artist COLLATE BINARY',
                (q, q) if q else ())]
        elif path == '/api/charts':
            rows = self.db.execute('''SELECT s.id,s.data,COALESCE(h.plays,0) AS play_count
                FROM catalog_search x JOIN current_guids c ON x.guid=c.guid JOIN songs s ON s.id=x.song_id
                LEFT JOIN (SELECT song_id,count(*) AS plays FROM history GROUP BY song_id) h ON h.song_id=s.id
                ORDER BY play_count DESC,s.id ASC LIMIT 100''')
            data = [dict(self.decode(row), play_count=row['play_count']) for row in rows]
        else:
            conditions, params = [], []
            if q:
                conditions.append('(instr(x.title_artist,?)>0 OR instr(x.title_artist_initials,?)>0)')
                params.extend((q, q))
            if artist:
                conditions.append('(instr(x.artist_fold,?)>0 OR instr(x.artist_initials,?)>0)')
                params.extend((artist, artist))
            if kind:
                conditions.append('x.media_kind=?')
                params.append(kind)
            where = ' WHERE '+' AND '.join(conditions) if conditions else ''
            base = ' FROM catalog_search x JOIN current_guids c ON x.guid=c.guid'
            count_key = (q, artist, kind)
            total = self.counts.get(count_key)
            if total is None:
                total = self.db.execute('SELECT count(*)'+base+where, params).fetchone()[0]
                if len(self.counts) >= 64:
                    self.counts.popitem(last=False)
                self.counts[count_key] = total
            items = []
            if start < total:
                rows = self.db.execute('SELECT s.id,s.data'+base+' JOIN songs s ON s.id=x.song_id'+where+
                                       ' ORDER BY x.song_id LIMIT ? OFFSET ?', (*params,size,start))
                items = [self.decode(row) for row in rows]
            data = {'items':items, 'total':total, 'catalogRevision':revision, 'catalogToken':token}
        body = json.dumps(data, ensure_ascii=False, separators=(',',':')).encode('utf-8')
        self._cache(key, body)
        return web.Response(body=body, content_type='application/json', charset='utf-8', headers=headers)
