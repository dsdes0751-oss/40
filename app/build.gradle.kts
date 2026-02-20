plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)

    //google-services 플러그인과 앱에서 사용할 Firebase SDK를 모두 추가하는 두개의 문장. (중요하다는뜻)

    id("com.google.gms.google-services")
    id("org.jetbrains.kotlin.kapt") // [수정] 올바른 플러그인 ID 사용
}

android {
    namespace = "com.tuna.proj_01"
    compileSdk = 36 // [수정] 일반적인 문법으로 변경

    defaultConfig {
        applicationId = "com.tuna.proj_01"
        minSdk = 24
        targetSdk = 36
        versionCode = 2
        versionName = "1.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
    kotlinOptions {
        jvmTarget = "11"
    }
}

dependencies {

    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.appcompat)
    implementation(libs.material)
    implementation(libs.androidx.activity)
    implementation(libs.androidx.constraintlayout)
    testImplementation(libs.junit)
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.espresso.core)

    //Firebase SDK를 사용하기위한 존나중요한 문장.
    implementation(platform("com.google.firebase:firebase-bom:34.8.0"))
    implementation("com.google.firebase:firebase-analytics")

    //로그인기능이래 그리고 아래에는 로그인 팝업을 띄우는 거래
    implementation("com.google.firebase:firebase-auth")
    implementation("com.google.android.gms:play-services-auth:21.5.0")

    //앱과 데이터베이스 연결
    implementation("com.google.firebase:firebase-firestore")
    //서버Cloud Functions와 대화하는 도구
    implementation("com.google.firebase:firebase-functions")


    // 구글 ML Kit (일본어 글자)
    implementation("com.google.mlkit:text-recognition-japanese:16.0.1")

    // 1. 영어 (및 라틴어 계열)
    implementation("com.google.mlkit:text-recognition:16.0.1")

// 2. 한국어
    implementation("com.google.mlkit:text-recognition-korean:16.0.1")

// 3. 중국어 (간체/번체 포함)
    implementation("com.google.mlkit:text-recognition-chinese:16.0.1")
    //사진확대기ㅡㄴㅇ
    implementation("com.github.chrisbanes:PhotoView:2.3.0")

    implementation("com.github.bumptech.glide:glide:5.0.5")

    implementation("androidx.activity:activity-ktx:1.8.0")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.6.2")
    implementation("androidx.lifecycle:lifecycle-viewmodel-ktx:2.6.2")

    implementation("com.google.mlkit:language-id:17.0.6")
    implementation("com.google.mlkit:translate:17.0.2")

    // [Room Database 추가]
    val room_version = "2.6.1"
    implementation("androidx.room:room-runtime:$room_version")
    implementation("androidx.room:room-ktx:$room_version") // 코루틴 지원
    kapt("androidx.room:room-compiler:$room_version")


    // [New] Google Play Billing Library
    implementation("com.android.billingclient:billing-ktx:6.1.0")

    // [New] Coroutines Play Services (Task await 사용을 위해)
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-play-services:1.7.3")
}