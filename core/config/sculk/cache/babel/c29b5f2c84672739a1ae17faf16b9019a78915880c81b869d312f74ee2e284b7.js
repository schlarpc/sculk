// ==Sculk==
// @name        Listener
// @description hears ctl-script-435700044241573
// ==/Sculk==
var mine = 41;
sculk.on('packet', (e) => {if (e.channel && e.channel.indexOf('ctl-script-435700044241573') >= 0) sculk.emit({ heard: 'ctl-script-435700044241573' });});
sculk.log('started ' + sculk.name);
mine + 1;