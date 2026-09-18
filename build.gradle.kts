// Top-level build file where you can add configuration options common to all sub-projects/modules.
plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.kotlin.compose) apply false
    // KSP 只是 Room 的注解处理器，本模块自己不产生代码；`apply false` 是让
    // 版本解析在根项目统一发生，子模块用 `alias` 引用时不必再写版本号。
    alias(libs.plugins.ksp) apply false
}