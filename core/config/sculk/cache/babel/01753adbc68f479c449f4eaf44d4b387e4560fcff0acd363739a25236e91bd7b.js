var leftover = 1;
sculk.on('packet', (e) => {if (e.channel && e.channel.indexOf('ctl-reload-435246351803262') >= 0) sculk.emit({ heard: 'ctl-reload-435246351803262' });});