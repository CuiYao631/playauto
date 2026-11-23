/*
 * 全量命令统计 v4：用反射读 cmd（字段 c 与方法 c() 冲突）
 */
function log(s) { send(String(s)); }

Java.perform(function () {
    var G = Java.use('cn.manstep.phonemirrorBox.BoxInterface.g');
    var I = Java.use('cn.manstep.phonemirrorBox.BoxInterface.i');
    var counts = {};
    var sub42 = {};
    var navJson = [];
    var mediaJson = 0;

    function cmdOf(n) {
        try {
            var nf = n.getClass().getDeclaredField('a');  // n.a() 是方法，须反射
            nf.setAccessible(true);
            var hdr = nf.get(n);
            var f = hdr.getClass().getDeclaredField('c');
            f.setAccessible(true);
            var v = f.get(hdr);
            var raw = (v && v.value !== undefined) ? v.value : v;
            return parseInt('' + raw, 10);
        } catch (e) { return -999; }
    }
    function lenOf(n) {
        try { return n.d(); } catch (e) { return -1; }
    }

    G.f0.implementation = function (n) {
        var cmd = cmdOf(n);
        counts[cmd] = (counts[cmd] || 0) + 1;
        if (cmd === 42) {
            try {
                var sub = n.b().getInt(0);
                sub42[sub] = (sub42[sub] || 0) + 1;
                if (sub === 200) {
                    var L = lenOf(n);
                    var JStr = Java.use('java.lang.String');
                    var body = String(JStr.$new(n.c(), 4, L - 4, 'UTF-8'));
                    navJson.push(body);
                    log('[200!] ' + body);
                } else if (sub === 1) {
                    var L2 = lenOf(n);
                    var JStr2 = Java.use('java.lang.String');
                    var b2 = String(JStr2.$new(n.c(), 4, L2 - 4, 'UTF-8'));
                    if (b2.indexOf('MediaLyrics') >= 0 || b2.indexOf('MediaSongName') >= 0) {
                        mediaJson++;
                        log('[media] ' + b2);
                    }
                }
            } catch (e) { log('[42err] ' + e + ' :: ' + (e.stack||'').split('\n')[1]); }
        }
        return this.f0(n);
    };
    log('[i] hooked f0 with reflection cmd');

    var last = Date.now();
    var t = setInterval(function () {
        if (Date.now() - last >= 10000) {
            last = Date.now();
            log('=== cmd=' + JSON.stringify(counts) +
                ' 42sub=' + JSON.stringify(sub42) +
                ' nav200=' + navJson.length + ' media=' + mediaJson);
        }
    }, 5000);
    setTimeout(function () { clearInterval(t); }, 300000);

    log('[i] ready');
});
