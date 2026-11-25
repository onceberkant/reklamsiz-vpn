// Sürümler ics-openvpn ile aynı tutuluyor (AGP 9.4.1, Kotlin 2.4.10).
// AGP 9'un yerleşik Kotlin desteği kullanılıyor; kotlin-android eklentisi yalnızca
// doğru Kotlin sürümünü sınıf yoluna koymak için burada "apply false" olarak duruyor.
plugins {
    id("com.android.application") version "9.4.1" apply false
    id("com.android.library") version "9.4.1" apply false
    id("org.jetbrains.kotlin.android") version "2.4.10" apply false
    id("org.jetbrains.kotlin.plugin.compose") version "2.4.10" apply false
}
