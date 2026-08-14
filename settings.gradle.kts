pluginManagement {
    repositories {
        // 国内镜像优先
        maven(url = "https://maven.aliyun.com/repository/gradle-plugin")
        maven(url = "https://maven.aliyun.com/repository/google")
        maven(url = "https://maven.aliyun.com/repository/public")
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}
dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        // 国内镜像优先
        maven(url = "https://maven.aliyun.com/repository/google")
        maven(url = "https://maven.aliyun.com/repository/public")
        maven(url = "https://maven.aliyun.com/repository/central")
        google()
        mavenCentral()
        maven(url = "https://jitpack.io")
        maven(url = "https://xdcobra.github.io/maven/")
    }
}

rootProject.name = "LingShu"
include(":app")
include(":core-common")
include(":core-ui")
include(":core-data")
include(":feature-guide")
include(":feature-chat")
include(":feature-stt")
include(":feature-wakeword")
include(":feature-control")
include(":feature-accessibility")
include(":feature-memory")
include(":feature-persona")
include(":feature-proactive")
include(":feature-clonevoice")
include(":feature-mod")
include(":feature-health")
include(":feature-rag")
include(":feature-floating")
include(":feature-update")
include(":feature-offlinestt")
include(":feature-offlinetts")
