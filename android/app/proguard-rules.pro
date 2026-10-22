# 릴리스에서 로그 호출 제거
-assumenosideeffects class android.util.Log {
    public static int v(...);
    public static int d(...);
    public static int i(...);
    public static int w(...);
    public static int e(...);
}

# 잠긴 폴더를 잠긴 채로(2.3.1, §19.6): Tink(안의 protobuf lite 규칙은 jar가 함께 준다)·BouncyCastle이 컴파일 때만 쓰는 주석·JDK 전용 클래스
-dontwarn com.google.errorprone.annotations.**
-dontwarn javax.annotation.**
-dontwarn com.google.api.client.**
-dontwarn org.joda.time.**
-dontwarn javax.naming.**
