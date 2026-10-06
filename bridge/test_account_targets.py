import unittest
from test_player_targets import Bridge, Socket
from player_targets import PlayerTargets


class AccountTargetTests(unittest.IsolatedAsyncioTestCase):
    async def asyncSetUp(self):
        self.b=Bridge();self.tv=Socket();self.phone=Socket();self.tablet=Socket();self.other=Socket()
        self.b.sockets={self.tv:'tv',self.phone:'phone',self.tablet:'controller',self.other:'other'}
        self.b.socket_requests={ws:True for ws in self.b.sockets}
        self.r=PlayerTargets(self.b)
        await self.r.announce(self.phone,dict(independentPlayer=True),'phone')

    async def request(self,device=''):
        self.r.selected[self.tablet]=device
        await self.r.receive(self.tablet,dict(type='account_request',requestId='ui',action='list',password='must-not-relay'))
        player=self.phone if device else self.tv
        self.assertNotIn('password',player.messages[-1])
        return player,player.messages[-1]['requestId']

    async def test_tv_and_phone_selected_routing_strips_credentials(self):
        for device in ('','phone'):
            player,token=await self.request(device)
            await self.r.receive(player,dict(type='account_reply',requestId=token,accounts=[dict(id='a',name='NAS',enabled=True,count=9,token='secret',password='secret')]))
            reply=self.tablet.messages[-1]
            self.assertEqual(device,reply['targetDeviceId']);self.assertEqual('ui',reply['requestId'])
            self.assertNotIn('token',reply['accounts'][0]);self.assertNotIn('password',reply['accounts'][0])

    async def test_wrong_sender_and_switch_cannot_publish_old_target(self):
        player,token=await self.request('phone')
        n=len(self.tablet.messages)
        await self.r.receive(self.other,dict(type='account_reply',requestId=token))
        self.assertEqual(n,len(self.tablet.messages))
        self.r.selected[self.tablet]=''
        await self.r.receive(player,dict(type='account_reply',requestId=token))
        self.assertEqual(n,len(self.tablet.messages))

    async def test_unauthorized_controller_cannot_change_account(self):
        self.b.socket_requests[self.tablet]=False
        await self.r.receive(self.tablet,dict(type='account_request',requestId='ui',action='disable',sourceId='a'))
        self.assertEqual([],self.tv.messages)
