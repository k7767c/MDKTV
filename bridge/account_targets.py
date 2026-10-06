"""Selected-player account controls. Metadata only: never relay credentials or URLs."""
import time
import uuid


class AccountTargets:
    def __init__(self, targets):
        self.targets = targets
        self.pending = {}

    async def receive(self, ws, message):
        targets = self.targets
        bridge = targets.bridge
        now = time.monotonic()
        self.pending = {k: v for k, v in self.pending.items() if now-v[4] < 120}
        if not bridge.authed(bridge.socket_requests.get(ws)):
            return True
        if message.get('type') == 'account_request':
            device = targets.selected.get(ws, '')
            player = targets.phone(device) if device else next((s for s, ident in bridge.sockets.items()
                if ident == bridge.player and ident and not s.closed), None)
            request = message.get('requestId')
            action = message.get('action')
            source = message.get('sourceId', '')
            if not isinstance(request, str) or not 0 < len(request) <= 80:
                return True
            if action not in ('list', 'sync', 'enable', 'disable', 'open'):
                return True
            if not isinstance(source, str) or len(source) > 128:
                return True
            if player is None or not bridge.authed(bridge.socket_requests.get(player)):
                await ws.send_json(dict(type='account_reply', requestId=request, targetDeviceId=device, error='所选播放设备不在线'))
                return True
            if sum(v[0] is ws for v in self.pending.values()) >= 4:
                return True
            token = uuid.uuid4().hex
            self.pending[token] = (ws, player, device, request, now)
            await player.send_json(dict(type='account_request', requestId=token, action=action, sourceId=source))
            return True
        pending = self.pending.get(message.get('requestId'))
        if not pending or pending[1] is not ws:
            return True
        controller, player, device, request, _ = self.pending.pop(message['requestId'])
        if controller.closed or targets.selected.get(controller, '') != device or not bridge.authed(bridge.socket_requests.get(controller)):
            return True
        rows = message.get('accounts', [])
        if not isinstance(rows, list):
            rows = []
        clean = []
        for row in rows[:100]:
            if not isinstance(row, dict):
                continue
            clean.append({k: str(row.get(k, ''))[:160] for k in ('id', 'name', 'provider', 'status')} |
                {'enabled': row.get('enabled') is True, 'count': min(1000000, max(0, row.get('count', 0))) if type(row.get('count')) is int else 0})
        await controller.send_json(dict(type='account_reply', requestId=request, targetDeviceId=device,
            accounts=clean, error=str(message.get('error', ''))[:160], message=str(message.get('message', ''))[:160]))
        return True
