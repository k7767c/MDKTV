"""Authenticated socket routing; independent phones never own the shared TV queue."""
import time
import uuid
import base64


class PlayerTargets:
    def __init__(self, bridge):
        self.bridge = bridge
        self.phones = {}  # socket -> announced phone identity
        self.selected = {}  # controller socket -> phone identity (empty means TV)
        self.states = {}
        self.delivered = {}
        self.catalog_requests = {}
        self.artwork_requests = {}
        from account_targets import AccountTargets
        self.accounts = AccountTargets(self)

    def phone(self, device):
        return next((ws for ws, info in self.phones.items() if info['id'] == device and not ws.closed), None)

    def list_message(self):
        return {'type': 'playback_devices', 'phones': [info for ws, info in self.phones.items() if not ws.closed]}

    def receives_tv(self, ws):
        return not self.selected.get(ws)

    async def refresh(self):
        await self.bridge.broadcast(self.list_message())
        for ws, device in list(self.selected.items()):
            if device and not ws.closed:
                await self.snapshot(ws, device)

    async def snapshot(self, ws, device):
        if not self.bridge.authed(self.bridge.socket_requests[ws]):
            return
        phone = self.phone(device)
        info = self.phones.get(phone, {})
        ack = {'type': 'target_ack', 'targetDeviceId': device, 'online': phone is not None,
               'deviceName': info.get('name', '播放设备（离线）'), 'catalogVersion': info.get('catalogVersion', 0)}
        state = self.states.get(device) if phone else None
        queue = {'type': 'queue', 'data': state.get('queue', []) if state else [], 'targetDeviceId': device}
        old = self.delivered.get(ws, {})
        if ack != old.get('ack'):
            await ws.send_json(ack)
        if queue != old.get('queue'):
            await ws.send_json(queue)
        self.delivered[ws] = {'ack': ack, 'queue': queue}
        if state and state.get('progress'):
            await ws.send_json(dict(state['progress'], type='progress', targetDeviceId=device))

    async def receive(self, ws, message):
        kind = message.get('type')
        if kind in ('account_request', 'account_reply'):
            return await self.accounts.receive(ws, message)
        if kind in ('artwork_request','artwork_reply'):
            return await self.artwork_message(ws,message)
        if kind in ('catalog_request', 'catalog_page'):
            return await self.catalog_message(ws, message)

        if kind == 'target_select':
            self.delivered.pop(ws, None)
            device = str(message.get('targetDeviceId', ''))[:128]
            self.selected[ws] = device
            if device:
                await self.snapshot(ws, device)
            else:
                await ws.send_json({'type': 'target_ack', 'targetDeviceId': '', 'online': bool(self.bridge.player), 'deviceName': self.bridge.player_name})
                await ws.send_json({'type': 'queue', 'data': self.bridge.queue()})
                await ws.send_json(self.bridge.player_message())
            return True
        if kind == 'phone_state':
            info = self.phones.get(ws)
            if not info or not isinstance(message.get('queue'), list) or not isinstance(message.get('progress'), dict):
                return True
            device = info['id']
            self.states[device] = {'queue': message['queue'], 'progress': message['progress']}
            for controller, target in list(self.selected.items()):
                if target == device and not controller.closed and self.bridge.authed(self.bridge.socket_requests[controller]):
                    await self.snapshot(controller, device)
            return True
        if kind == 'control' and self.selected.get(ws):
            device = self.selected[ws]
            phone = self.phone(device)
            if phone is None:
                await self.snapshot(ws, device)
                return True  # never fall back to TV
            control = dict(message, type='phone_control', targetDeviceId=device)
            if message.get('action') not in ('enqueue', 'queue_top', 'queue_remove', 'next', 'play_pause', 'voice', 'voice_set', 'repeat', 'seek', 'audio', 'volume', 'fullscreen'):
                return True
            if message.get('action') == 'enqueue':
                key = message.get('remote_key')
                if isinstance(key, str) and 0 < len(key) <= 160 and self.phones[phone].get('catalogVersion') == 1:
                    # Resolve this opaque key only on the selected player. Never accept media URLs.
                    control = {k: control[k] for k in ('type','targetDeviceId','action','commandId') if k in control}
                    control['remote_key'] = key
                    if self.bridge.authed(self.bridge.socket_requests[phone]):
                        await phone.send_json(control)
                    return True
                sid = message.get('song_id')
                if type(sid) is not int or sid <= 0:
                    return True
                # Trusted catalog metadata, never accept file paths/URLs from controllers.
                control['song'] = self.bridge.song(sid)
            if self.bridge.authed(self.bridge.socket_requests[phone]):
                await phone.send_json(control)
            return True
        return False

    async def announce(self, ws, message, device):
        if message.get('independentPlayer') is True:
            self.phones[ws] = {'id': device, 'name': str(message.get('deviceName', '安卓手机'))[:80], 'catalogVersion': 1 if message.get('catalogVersion') == 1 else 0}
            await self.refresh()

    async def remove(self, ws):
        self.artwork_requests = {k:v for k,v in self.artwork_requests.items() if ws not in (v[0],v[1])}
        self.catalog_requests = {k: v for k, v in self.catalog_requests.items() if ws not in (v[0], v[1])}
        self.selected.pop(ws, None)
        self.delivered.pop(ws, None)
        info = self.phones.pop(ws, None)
        if info:
            if self.phone(info['id']) is None:
                self.states.pop(info['id'], None)
            await self.refresh()

    async def catalog_message(self, ws, message):
        now = time.monotonic()
        self.catalog_requests = {k: v for k, v in self.catalog_requests.items() if now-v[4] < 30}
        if not self.bridge.authed(self.bridge.socket_requests.get(ws)):
            return True
        if message['type'] == 'catalog_request':
            device = self.selected.get(ws)
            player = self.phone(device)
            request = str(message.get('requestId', ''))[:80]
            offset = message.get('offset', 0)
            if not request or type(offset) is not int or not 0 <= offset <= 500000:
                return True
            if not player or self.phones[player].get('catalogVersion') != 1:
                await ws.send_json(dict(type='catalog_page', requestId=request, targetDeviceId=device,
                                        items=[], offset=offset, more=False, error='请更新并打开所选播放端'))
                return True
            if not self.bridge.authed(self.bridge.socket_requests.get(player)):
                return True
            # One outstanding page per controller prevents resource amplification.
            self.catalog_requests = {k:v for k,v in self.catalog_requests.items() if v[0] is not ws}
            token = uuid.uuid4().hex
            self.catalog_requests[token] = (ws, player, device, request, now, offset)
            await player.send_json(dict(type='catalog_request', requestId=token, offset=offset, limit=400))
            return True
        token = message.get('requestId')
        pending = self.catalog_requests.get(token)
        if not pending or pending[1] is not ws:
            return True
        controller, player, device, request, _, offset = self.catalog_requests.pop(token)
        if controller.closed or self.selected.get(controller) != device or not self.bridge.authed(self.bridge.socket_requests.get(controller)):
            return True
        items = message.get('items')
        if not isinstance(items, list) or len(items) > 400:
            return True
        safe = []
        for row in items:
            if not isinstance(row, dict) or not isinstance(row.get('remote_key'), str) or not 0 < len(row['remote_key']) <= 160:
                continue
            value = {key:str(row.get(key, ''))[:300] for key in ('remote_key','title','artist','album','genre','media_kind','format','provider')}
            if isinstance(row.get('artist_names'),list):value['artist_names']=[n[:120] for n in row['artist_names'][:20] if isinstance(n,str)]
            safe.append(value)
        await controller.send_json(dict(type='catalog_page', requestId=request, targetDeviceId=device,
            offset=offset, items=safe, more=message.get('more') is True and bool(items),
            error=str(message.get('error', ''))[:100]))
        return True

    async def artwork_message(self, ws, message):
        now=time.monotonic()
        self.artwork_requests={k:v for k,v in self.artwork_requests.items() if now-v[4]<20}
        if not self.bridge.authed(self.bridge.socket_requests.get(ws)):return True
        if message['type']=='artwork_request':
            device=self.selected.get(ws);player=self.phone(device)
            request=str(message.get('requestId',''))[:80]
            key=message.get('remote_key');kind=message.get('kind','track')
            if not request or not isinstance(key,str) or not 0<len(key)<=160 or kind not in ('track','artist','album'):return True
            if not player or not self.bridge.authed(self.bridge.socket_requests.get(player)) or sum(v[0] is ws for v in self.artwork_requests.values())>=4:
                await ws.send_json(dict(type='artwork_reply',requestId=request,targetDeviceId=device,image=''));return True
            token=uuid.uuid4().hex;self.artwork_requests[token]=(ws,player,device,request,now)
            await player.send_json(dict(type='artwork_request',requestId=token,remote_key=key,kind=kind,name=str(message.get('name',''))[:300]))
            return True
        token=message.get('requestId');pending=self.artwork_requests.get(token)
        if not pending or pending[1] is not ws:return True
        controller,player,device,request,_=self.artwork_requests.pop(token)
        if controller.closed or self.selected.get(controller)!=device or not self.bridge.authed(self.bridge.socket_requests.get(controller)):return True
        image=message.get('image','')
        if not isinstance(image,str) or len(image)>32000:image=''
        try:
            data=base64.b64decode(image,validate=True)
            if data and not data.startswith(b'\xff\xd8\xff'):image=''
        except ValueError:image=''
        await controller.send_json(dict(type='artwork_reply',requestId=request,targetDeviceId=device,image=image))
        return True
