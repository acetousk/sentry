## A sentry component

### sentry Component with: 

- Tool factory

### Setup
- Run a sentry open source compatible server like [glitchtip](https://glitchtip.com/)
- Add the following to your `moqui-framework` `myaddons.xml` https://github.com/acetousk/moqui-framework/blob/c58148dd5b6b185a1d15f70ef4e33b018a6ba9cb/myaddons.xml#L36 
```xml
<component group="acetousk" name="sentry" branch="master"/>
```
- Set a property or environment variable of your Sentry DSN link to `sentry_dsn` would be at https://${yourglitchtipinstance}.com/${yourorganization}/settings/projects/${yourproject}
- Run your Moqui instance and see warn and error logs from your Moqui instance

