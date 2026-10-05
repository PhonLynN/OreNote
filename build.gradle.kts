// OrePlan 根构建脚本：只做插件声明，不在此处配置任何模块逻辑。
// 注意：AGP 9 起内置 Kotlin 支持，不再需要 org.jetbrains.kotlin.android 插件。
plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.kotlin.compose) apply false
    alias(libs.plugins.ksp) apply false
    alias(libs.plugins.hilt) apply false
    alias(libs.plugins.room) apply false
}
