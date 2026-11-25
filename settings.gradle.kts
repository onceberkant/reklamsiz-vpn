pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
    }
}

rootProject.name = "reklamsiz-vpn"

// :app      -> Kotlin + Compose arayüz, VPNGate istemcisi
// :openvpn  -> OpenVPN for Android (ics-openvpn) çekirdeği, kütüphane olarak
include(":app", ":openvpn")
