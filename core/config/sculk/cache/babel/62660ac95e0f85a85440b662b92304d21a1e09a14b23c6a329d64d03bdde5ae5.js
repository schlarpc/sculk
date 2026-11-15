// ==Sculk==
// @name        Listener
// @description hears ctl-script-435640144273117
// ==/Sculk==
var mine = 41;
sculk.on('packet', (e) => {if (e.channel && e.channel.indexOf('ctl-script-435640144273117') >= 0) sculk.emit({ heard: 'ctl-script-435640144273117' });});
sculk.log('started ' + sculk.name);
mine + 1;