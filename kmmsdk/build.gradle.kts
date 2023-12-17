plugins {
    alias(libs.plugins.kotlinMultiplatform)
    alias(libs.plugins.androidLibrary)
    alias(libs.plugins.serialization)
    alias(libs.plugins.sqldelight)
    //alias(libs.plugins.kotlinAndroid)

}

kotlin {
    androidTarget {
        compilations.all {
            kotlinOptions {
                jvmTarget = "1.8"
            }
        }
    }
    
    listOf(
        iosX64(),
        iosArm64(),
        iosSimulatorArm64()
    ).forEach {
        it.binaries.framework {
            baseName = "kmmsdk"
            isStatic = true
        }
    }

    sourceSets {
        commonMain.dependencies {
            //put your multiplatform dependencies here
            //coroutines
            implementation(libs.kotlinx.coroutines.core)
            //ktor
            implementation(libs.ktor.client.core)
            implementation(libs.ktor.client.content.negotiation)
            implementation(libs.ktor.client.serialization)
            //ktor logger
            implementation(libs.slf4j.logger)
            //shared pref
            implementation(libs.russhwolf.settings)
            implementation(libs.russhwolf.settings.no.arg)

        }
        commonTest.dependencies {
            implementation(libs.kotlin.test)
        }

        androidMain.dependencies {
            implementation(libs.ktor.client.android)
            implementation(libs.sqldelight.driver.android)
            implementation(libs.altbeacon.android)
            implementation(libs.androidx.startup)
            implementation(libs.retrofit)
            implementation(libs.converter.gson)
        }

        iosMain.dependencies {
            implementation(libs.ktor.client.ios)
            implementation(libs.sqldelight.driver.native)
        }
    }
}

android {
    namespace = "com.lokate.kmmsdk"
    compileSdk = 34
    defaultConfig {
        minSdk = 24
    }
}

dependencies {
    implementation(libs.core.ktx)
}

sqldelight{
    databases{
        create("Database"){
            packageName.set("com.lokate.kmmsdk")
        }
    }
    linkSqlite = true
}
