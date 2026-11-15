// ==Sculk==
// @name        Listener
// @description hears ctl-script-435973715499849
// ==/Sculk==
var mine = 41;
sculk.on('packet', (e) => {if (e.channel && e.channel.indexOf('ctl-script-435973715499849') >= 0) sculk.emit({ heard: 'ctl-script-435973715499849' });});
sculk.log('started ' + sculk.name);
mine + 1;