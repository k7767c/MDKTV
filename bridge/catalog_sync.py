"""Independent metadata snapshots. Failed sources retain their last complete snapshot."""
import asyncio
import json
import time
from catalog_queries import CatalogQueries


class CatalogSync:
    def __init__(self, bridge):
        self.b = bridge
        self.locks = {k: asyncio.Lock() for k in ('music', 'video', 'quark')}
        self.status = {k: {'state': 'pending', 'count': 0} for k in self.locks}
        self.tasks = {}
        self.revision = 0
        db = bridge.db
        existed = db.execute("SELECT 1 FROM sqlite_master WHERE name='current_guids'").fetchone()
        db.execute('CREATE TABLE IF NOT EXISTS current_guids(guid TEXT PRIMARY KEY)')
        if not existed:
            db.execute('INSERT OR IGNORE INTO current_guids SELECT guid FROM songs')
        db.commit()
        self.queries = CatalogQueries(db)

    async def sync(self, kind):
        if self.locks[kind].locked():
            return
        async with self.locks[kind]:
            state = self.status[kind]
            source = getattr(getattr(self.b, 'sources', None), kind, None)
            generation = source.generation if source is not None else None
            state.update(state='syncing', startedAt=time.time())
            try:
                if kind == 'quark':
                    rows = await self.b.quark_rows()
                else:
                    rows = await (self.b.music_rows() if kind == 'music' else
                        (self.b.video_rows() if hasattr(self.b, 'video_rows') else self.b.videos.scan()))
                if generation is not None and generation != source.generation:
                    return  # An old account scan must never publish after switching/logging out.
                db = self.b.db
                clause = ("guid NOT LIKE 'video:%' AND guid NOT LIKE 'fnvideo:%' AND guid NOT LIKE 'quark:%'" if kind == 'music' else
                          "guid LIKE 'quark:%'" if kind == 'quark' else "(guid LIKE 'video:%' OR guid LIKE 'fnvideo:%')")
                old = {r['guid']: json.loads(r['data']) for r in db.execute(
                    'SELECT * FROM songs WHERE guid IN (SELECT guid FROM current_guids WHERE '+clause+')')}
                state.update(state='ready', count=len(rows), completedAt=time.time(), error='')
                if rows != old:
                    # Unchanged snapshots must not churn the index/cache token.
                    # Only changed/new metadata and visibility are written.
                    with db:
                        for guid, data in rows.items():
                            if old.get(guid) == data:
                                continue
                            db.execute('INSERT INTO songs(guid,data) VALUES(?,?) ON CONFLICT(guid) DO UPDATE SET data=excluded.data',
                                       (guid, json.dumps(data, ensure_ascii=False)))
                        db.executemany('DELETE FROM current_guids WHERE guid=?', ((g,) for g in old.keys()-rows.keys()))
                        db.executemany('INSERT OR IGNORE INTO current_guids VALUES(?)', ((g,) for g in rows.keys()-old.keys()))
                    self.revision += 1
                    await self.b.broadcast({'type': 'catalog_changed', 'revision': self.revision, 'source': kind})
            except asyncio.CancelledError:
                state['state'] = 'cancelled'
                raise
            except Exception:
                # Exception text may contain upstream URLs or secrets.
                state.update(state='offline', error='来源暂不可用，保留上次完整曲库')

    async def loop(self, kind):
        interval = max(15, int(self.b.cfg.get('catalog_sync_seconds', 60)))
        while True:
            await self.sync(kind)
            await asyncio.sleep(interval)

    def start(self):
        if self.b.cfg.get('catalog_sync_enabled', True):
            self.tasks = {k: asyncio.create_task(self.loop(k)) for k in ('music', 'video')}

    async def close(self):
        for task in self.tasks.values():
            task.cancel()
        await asyncio.gather(*self.tasks.values(), return_exceptions=True)
