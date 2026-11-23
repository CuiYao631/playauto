/*
 * 观察盒子"导航开始/结束"通知：cmd 8 / 506 → b1.c.L(1)，507 → L(0)
 */
function log(s) { send(String(s)); }
Java.perform(function () {
    var BC = Java.use('cn.manstep.phonemirrorBox.b1.c');
    var inst = BC.i();
    var G = Java.use('cn.manstep.phonemirrorBox.BoxInterface.g');
    var sub42 = {};

    BC.L.implementation = function (v) {
        log('[NAV-STATE] b1.c.L(' + v + ')  ← ' + (v === 1 ? '导航开始' : '导航结束'));
        return this.L(v);
    };
    log('[i] hooked b1.c.L');

    G.P.implementation = function (n) {
        try {
            var sub = n.b().getInt(0);
            sub42[sub] = (sub42[sub] || 0) + 1;
        } catch (e) {}
        return this.P(n);
    };
    log('[i] hooked g.P');

    // 读当前导航状态
    try {
        var f = inst.getClass().getDeclaredField('a');
    } catch (e) {}

    var last = Date.now();
    var t = setInterval(function () {
        if (Date.now() - last >= 10000) {
            last = Date.now();
            log('=== 42sub=' + JSON.stringify(sub42));
        }
    }, 5000);
    setTimeout(function () { clearInterval(t); }, 180000);
});
