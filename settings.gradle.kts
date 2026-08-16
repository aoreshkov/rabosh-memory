pluginManagement {
    repositories {
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositoriesMode = RepositoriesMode.FAIL_ON_PROJECT_REPOS
    repositories {
        mavenCentral()
    }
}

rootProject.name = "rabosh-memory"

// One module, deliberately. The engine is seven because its layers have to be separable; this is a
// handler, an options object and a path function, and splitting it would put a module boundary where
// there is no seam. `build-logic` is absent for the same reason: a convention plugin exists to stop
// several modules disagreeing, and there is nothing here to disagree with.
