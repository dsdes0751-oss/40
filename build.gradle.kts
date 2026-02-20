// Top-level build file where you can add configuration options common to all sub-projects/modules.
plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.kotlin.android) apply false
    //Firebase SDK가 google-services.json 구성 값에 액세스할 수 있도록 하려면 Google 서비스 Gradle 플러그인이 필요하며, 이건 그 문장. (대충 중요하다는뜻)
    id("com.google.gms.google-services") version "4.4.4" apply false
}