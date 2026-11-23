function log(s) { send(String(s)); }
Java.perform(function () {
    var G = Java.use('cn.manstep.phonemirrorBox.BoxInterface.g');

    // z0(String,Object) 与 K0(String,Object) 都是单键下发（cmd 25 / 160）
    G.z0.implementation = function (k, v) {
        log('[SEND z0] ' + k + ' = ' + v);
        return this.z0(k, v);
    };
    try {
        G.K0.implementation = function (k, v) {
            log('[SEND K0] ' + k + ' = ' + v);
            return this.K0(k, v);
        };
    } catch (e) {}

    // p1(byte[]) 是 cmd 25 的原始 JSON
    G.p1.implementation = function (b) {
        try {
            var s = String(Java.use('java.lang.String').$new(b, 'UTF-8'));
            log('[SEND 25] ' + (s.length > 700 ? s.substring(0, 700) + '...' : s));
        } catch (e) { log('[SEND 25] <decode err>'); }
        return this.p1(b);
    };
    // q0(byte[]) = cmd 160
    G.q0.implementation = function (b) {
        try {
            var s = String(Java.use('java.lang.String').$new(b, 'UTF-8'));
            log('[SEND 160] ' + (s.length > 500 ? s.substring(0, 500) + '...' : s));
        } catch (e) {}
        return this.q0(b);
    };
    log('[i] 发送侧 hook 就绪');

    // 在副屏重新触发一次尺寸上报
    setTimeout(function () {
        Java.scheduleOnMainThread(function () {
            try {
                var PM = Java.use('cn.manstep.phonemirrorBox.w0.f').l();
                var f = PM.getClass().getDeclaredField('c');
                f.setAccessible(true);
                log('[i] 触发 O0() 重新下发 naviScreenInfo');
                PM.O0 ? PM.O0() : log('[i] 无 O0');
            } catch (e) { log('[!] ' + e); }
        });
    }, 2000);
});
