// 发验证码按钮的倒计时（注册/忘记密码在 auth 弹窗、改密码/换绑邮箱在设置页，共用这一份）。
//
// 为什么按 key 分开：设置页有三个按钮（改密码的码、旧邮箱的码、新邮箱的码）。它们发给不同邮箱或
// 不同用途，服务端彼此独立；共用一个倒计时会让用户在本来能立刻发的按钮上白等 60 秒。
//
// 这只是体验层的防连点。真正的限流在服务端（同邮箱一天 3 个、间隔 60 秒），而前后端时钟不可能
// 完全对齐，所以后端返回的"请 42 秒后再试"要原样显示给用户，以服务端为准。
import {reactive} from 'vue';

export const cooldowns = reactive({});

const timers = {};

/** 剩余秒数（模板里直接 byKey('auth') 用） */
export const byKey = (key) => cooldowns[key] || 0;

/** 开始倒计时（重复调用会重置） */
export const startCooldown = (key, seconds) => {
    cooldowns[key] = seconds;
    if (timers[key]) clearInterval(timers[key]);
    timers[key] = setInterval(() => {
        cooldowns[key] = (cooldowns[key] || 0) - 1;
        if (cooldowns[key] <= 0) {
            clearInterval(timers[key]);
            delete timers[key];
        }
    }, 1000);
};
