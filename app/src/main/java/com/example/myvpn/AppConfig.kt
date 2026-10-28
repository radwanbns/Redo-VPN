package com.example.myvpn

/** إعدادات تُدمج داخل التطبيق عند البناء (تصبح جزءاً من classes.dex). */
object AppConfig {
    /** رابط الاشتراك المدمج (raw من GitHub أو Gist). اتركه "" لتعطيل الميزة. */
    const val SUB_URL = "https://raw.githubusercontent.com/radwanbns/Redo-VPN/main/servers.txt"

    /** true = لا يستطيع المستخدم تغيير الرابط من الإعدادات. */
    const val LOCK_SUB = true
}
