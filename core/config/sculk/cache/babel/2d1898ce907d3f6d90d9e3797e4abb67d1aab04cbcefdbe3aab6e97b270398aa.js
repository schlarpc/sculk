// ==Sculk==
// @name        Listener
// @description hears ctl-script-436018250674413
// ==/Sculk==
var mine = 41;
sculk.on('packet', (e) => {if (e.channel && e.channel.indexOf('ctl-script-436018250674413') >= 0) sculk.emit({ heard: 'ctl-script-436018250674413' });});
sculk.log('started ' + sculk.name);
mine + 1;