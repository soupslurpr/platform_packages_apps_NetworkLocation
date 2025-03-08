//! Position estimation by multilateration using robust algorithms.

use jni::{objects::{JClass, JObjectArray}, sys::jobject, JNIEnv};

// SAFETY: There is no other global function of this name.
#[no_mangle]
pub extern "system" fn Java_app_grapheneos_networklocation_interop_multilateration_Multilateration_multilateration(
    mut env: JNIEnv,
    _class: JClass,
    measurements: JObjectArray,
) -> jobject {
    
}