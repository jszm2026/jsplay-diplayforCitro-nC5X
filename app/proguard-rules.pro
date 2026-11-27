# JSplay (DiPlay api19) R8 shrink rules.
# Goal: cut BouncyCastle's dead code (PQC/LDAP/SMIME/cert-path — the app only
# uses Ed25519/X25519/HKDF/ChaCha20-Poly1305/SHA512 + a few ASN1 classes, all
# called directly) and unused library surface. Direct calls survive shrinking;
# only reflective/native entry points must be kept.

# --- Custom View inflated from XML: R8 merges the (Context)/(Context, AttributeSet)
#     constructors into one signature; LayoutInflater reflection then fails on API 19
#     with InflateException. Keep the class and all its constructors intact. ---
-keep class com.shilapi.xcertplay.app.CornerOverlayView { <init>(...); }

# --- AirPlay crypto: R8 aggressive inlining/merging produced bytecode the
#     Android 9 (API 28) ART verifier rejects (VerifyError "instance-of on
#     unexpected class Integer" at startup). Keep it from being optimized. ---
-keep,allowobfuscation class com.shilapi.xcertplay.airplay.AirPlayCrypto { *; }

# --- R8 optimization pass (inlining BouncyCastle into crypto callers) emits
#     bytecode the Android 9 ART verifier rejects with VerifyError. Shrinking
#     (dead-code cut) and obfuscation stay on; only optimization is disabled. ---
-dontoptimize

# --- JNI: the two native libraries load and call Kotlin objects by name ---
-keepclasseswithmembernames class * {
    native <methods>;
}
-keep class com.shilapi.xcertplay.media.FdkAac { *; }

# --- JmDNS 3.6.3 (vendored source) uses Class.forName in listener paths ---
-keep class javax.jmdns.** { *; }
-dontwarn javax.jmdns.**

# --- BouncyCastle: only the light crypto/ASN1 subset is reachable; silence
#     warnings for the pruned remainder (optional deps it tries to detect) ---
-dontwarn org.bouncycastle.**
-dontwarn org.slf4j.**

# --- SLF4J no-op on Android ---
-dontwarn org.slf4j.impl.**
-dontwarn com.android.org.conscrypt.SSLParametersImpl
-dontwarn org.apache.harmony.xnet.provider.jsse.SSLParametersImpl

# --- Keep line numbers for readable crash logs on the head unit, but strip
#     file names to save space ---
-keepattributes SourceFile,LineNumberTable
-renamesourcefileattribute SourceFile

# --- CarPlay protocol structs are constructed via exhaustive when() over
#     sealed classes; R8 keeps those automatically. No JSON/serialization
#     reflection exists in this codebase (hand-written plist/RTP/iAP2). ---
