import unittest
from player_targets import PlayerTargets


class Socket:
    closed = False
    def __init__(self):
        self.messages = []
    async def send_json(self, message):
        self.messages.append(message)


class Bridge:
    player = 'tv'
    player_name = 'TV'
    def __init__(self):
        self.socket_requests = {}
        self.events = []
    async def broadcast(self, message):
        self.events.append(message)
    def authed(self, request):
        return request
    def song(self, sid):
        return {'song_id': sid, 'title': 'trusted catalog'}
    def queue(self):
        return [{'song_id': 9, 'queue_id': 1}]
    def player_message(self):
        return {'type': 'player_changed', 'activeDeviceId': 'tv'}


class TargetTests(unittest.IsolatedAsyncioTestCase):
    async def asyncSetUp(self):
        self.b = Bridge()
        self.r = PlayerTargets(self.b)
        self.phone, self.other, self.tablet = Socket(), Socket(), Socket()
        for ws in (self.phone, self.other, self.tablet):
            self.b.socket_requests[ws] = True
        await self.r.announce(self.phone, {'independentPlayer': True, 'deviceName': 'Phone'}, 'phone')
        await self.r.announce(self.other, {'independentPlayer': True, 'deviceName': 'Other'}, 'other')
        await self.r.receive(self.tablet, {'type': 'target_select', 'targetDeviceId': 'phone'})

    async def test_phone_enqueue_resolves_catalog_and_does_not_touch_tv_or_other_phone(self):
        await self.r.receive(self.tablet, {'type':'control','action':'enqueue','song_id':2,'song':{'local_uri':'evil'}})
        self.assertEqual('phone_control', self.phone.messages[-1]['type'])
        self.assertEqual({'song_id':2,'title':'trusted catalog'}, self.phone.messages[-1]['song'])
        self.assertEqual([], self.other.messages)
        self.assertEqual('tv', self.b.player)
        self.assertEqual(9, self.b.queue()[0]['song_id'])
        self.assertFalse(self.r.receives_tv(self.tablet))

    async def test_only_announced_phone_state_reaches_its_selected_controller(self):
        self.tablet.messages.clear()
        await self.r.receive(self.tablet, {'type':'phone_state','queue':[{'song_id':8}],'progress':{}})
        self.assertEqual({},self.r.states)
        await self.r.receive(self.other, {'type':'phone_state','queue':[{'song_id':3}],'progress':{}})
        self.assertEqual([],self.tablet.messages)
        state={'type':'phone_state','queue':[{'song_id':2,'queue_id':200}],'progress':{'queueId':200}}
        await self.r.receive(self.phone,state)
        self.assertEqual(2,next(m for m in self.tablet.messages if m['type']=='queue')['data'][0]['song_id'])
        self.tablet.messages.clear()
        await self.r.receive(self.phone,state)
        self.assertEqual(['progress'],[m['type'] for m in self.tablet.messages])

    async def test_disconnect_reconnect_retains_choice_never_falls_back_to_tv(self):
        await self.r.remove(self.phone)
        self.assertTrue(await self.r.receive(self.tablet, {'type':'control','action':'next'}))
        self.assertEqual('phone', self.r.selected[self.tablet])
        self.assertFalse(next(m for m in reversed(self.tablet.messages) if m['type']=='target_ack')['online'])
        await self.r.announce(self.phone, {'independentPlayer':True}, 'phone')
        self.assertTrue(any(m.get('online') is True for m in self.tablet.messages))
        await self.r.receive(self.tablet, {'type':'target_select','targetDeviceId':''})
        self.assertTrue(self.r.receives_tv(self.tablet))
        self.assertEqual(9,next(m for m in reversed(self.tablet.messages) if m['type']=='queue')['data'][0]['song_id'])

    async def test_expired_phone_is_not_controlled_and_expired_controller_gets_no_state(self):
        self.b.socket_requests[self.phone]=False
        await self.r.receive(self.tablet, {'type':'control','action':'next'})
        self.assertEqual([],self.phone.messages)
        self.b.socket_requests[self.tablet]=False
        self.tablet.messages.clear()
        await self.r.receive(self.other, {'type':'phone_state','queue':[],'progress':{}})
        await self.r.refresh()
        self.assertEqual([],self.tablet.messages)

    async def test_catalog_pages_are_bound_to_selected_player_and_strip_secrets(self):
        await self.r.announce(self.phone, {'independentPlayer':True,'catalogVersion':1}, 'phone')
        await self.r.receive(self.tablet, {'type':'catalog_request','requestId':'ui-1','offset':0})
        token=self.phone.messages[-1]['requestId']
        self.tablet.messages.clear()
        payload={'type':'catalog_page','requestId':token,'items':[{'remote_key':'opaque','title':'cloud track','local_path':'PRIVATE','cookie':'SECRET','url':'https://private'}],'more':False}
        await self.r.receive(self.other,payload)
        self.assertEqual([],self.tablet.messages)
        await self.r.receive(self.phone,payload)
        reply=self.tablet.messages[-1]
        self.assertEqual('ui-1',reply['requestId'])
        self.assertEqual('phone',reply['targetDeviceId'])
        self.assertFalse(any(k in reply['items'][0] for k in ('cookie','url','local_path')))
        await self.r.receive(self.tablet, {'type':'control','action':'enqueue','commandId':'one','remote_key':'opaque','song':{'url':'evil'}})
        self.assertEqual('opaque',self.phone.messages[-1]['remote_key'])
        self.assertNotIn('song',self.phone.messages[-1])

    async def test_switching_target_discards_late_catalog_page(self):
        await self.r.announce(self.phone, {'independentPlayer':True,'catalogVersion':1}, 'phone')
        await self.r.receive(self.tablet, {'type':'catalog_request','requestId':'old','offset':0})
        token=self.phone.messages[-1]['requestId']
        await self.r.receive(self.tablet, {'type':'target_select','targetDeviceId':'other'})
        self.tablet.messages.clear()
        await self.r.receive(self.phone, {'type':'catalog_page','requestId':token,'items':[]})
        self.assertEqual([],self.tablet.messages)

    async def test_old_player_reports_unsupported_instead_of_wrong_nas_library(self):
        await self.r.receive(self.tablet, {'type':'catalog_request','requestId':'x','offset':0})
        self.assertTrue(self.tablet.messages[-1]['error'])
        self.assertEqual([],self.tablet.messages[-1]['items'])

    async def test_artwork_is_bounded_and_bound_to_selected_device(self):
        import base64
        await self.r.receive(self.tablet,dict(type='artwork_request',requestId='cover',remote_key='opaque',kind='artist',name='Singer'))
        token=self.phone.messages[-1]['requestId'];self.tablet.messages.clear()
        reply=dict(type='artwork_reply',requestId=token,image=base64.b64encode(b'\xff\xd8\xffTEST').decode())
        await self.r.receive(self.other,reply)
        self.assertEqual([],self.tablet.messages)
        await self.r.receive(self.phone,reply)
        self.assertEqual('cover',self.tablet.messages[-1]['requestId'])
        self.assertEqual(reply['image'],self.tablet.messages[-1]['image'])
        for i in range(5):
            await self.r.receive(self.tablet,dict(type='artwork_request',requestId=str(i),remote_key='opaque',kind='track'))
        self.assertEqual(4,len(self.r.artwork_requests))
        self.assertEqual('',self.tablet.messages[-1]['image'])
        token=next(iter(self.r.artwork_requests))
        await self.r.receive(self.tablet,dict(type='target_select',targetDeviceId='other'))
        self.tablet.messages.clear()
        await self.r.receive(self.phone,dict(type='artwork_reply',requestId=token,image=reply['image']))
        self.assertEqual([],self.tablet.messages)
