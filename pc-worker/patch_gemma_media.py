"""Narrow idempotent upgrade of an installed worker, without changing its text-model configuration."""
def patch_server(text):
    marker='   # Gemma media shares the existing native admission lock.'
    if marker in text:return text
    old="  if self.command=='POST' and path=='/v1/media/describe':self.connection.settimeout(610);self.reply(MEDIA.describe(self.body()));return"
    new="""  if self.command=='POST' and path=='/v1/media/describe':
   # Gemma media shares the existing native admission lock.
   if not ENGINE.lock.acquire(timeout=1):raise Busy()
   try:
    self.connection.settimeout(610);self.reply(MEDIA.describe(self.body()))
   finally:ENGINE.lock.release()
   return"""
    if text.count(old)!=1:raise ValueError('MEDIA_ROUTE_BASELINE')
    text=text.replace(old,new)
    old_health="self.reply(ENGINE.health()"
    new_health="self.reply(MEDIA.health(ENGINE.health())"
    if text.count(old_health)!=1:raise ValueError('HEALTH_ROUTE_BASELINE')
    result=text.replace(old_health,new_health)
    compile(result,'server.py','exec')
    return result
