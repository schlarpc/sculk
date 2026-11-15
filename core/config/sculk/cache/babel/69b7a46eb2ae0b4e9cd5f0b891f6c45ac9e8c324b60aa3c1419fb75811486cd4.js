var leftover = 1;
sculk.on('packet', (e) => {if (e.channel && e.channel.indexOf('ctl-reload-435702596191041') >= 0) sculk.emit({ heard: 'ctl-reload-435702596191041' });});