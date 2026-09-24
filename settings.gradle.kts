pluginManagement {
    repositories {
        // 阿里云镜像优先（国内下载稳定），与现有 Flutter 工程一致
        maven { url = uri("https://maven.aliyun.com/repository/gradle-plugin") }
        maven { url = uri("https://maven.aliyun.com/repository/public") }
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}
dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.PREFER_SETTINGS)
    repositories {
        maven { url = uri("https://maven.aliyun.com/repository/public") }
        // 阿里云 google 专用镜像：代理 dl.google.com，避免 Room 等 artifact 下载超时
        maven { url = uri("https://maven.aliyun.com/repository/google") }
        google()
        mavenCentral()
    }
}

rootProject.name = "slides"
include(":app")
