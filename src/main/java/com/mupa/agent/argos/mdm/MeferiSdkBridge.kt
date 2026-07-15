package com.mupa.agent.argos.mdm

import android.os.IBinder
import android.os.Parcel
import android.util.Log

/**
 * Talks to the MEFERI device-manager binder service ("MefCoreService") via raw
 * Parcel transactions — MEFERI does not publish their SDK jar, so the interface
 * descriptors and transaction codes below were extracted from the firmware's
 * /system/app/SdkService/SdkService.apk (MC45, Android 14, build UQ1A.240205.004.B1):
 *
 *   ServiceManager.getService("MefCoreService")            → IProfileService binder
 *   IProfileService  txn 3  getDeviceManagerService()      → IDeviceManager binder
 *   IDeviceManager   txn 5  setNavigationbarenable(bool)   → bool
 *
 * The service applies the change by writing the "navbar_enable" System setting as a
 * platform app — which our agent cannot do directly (custom System-table keys are
 * rejected for targetSdk ≥ 23 callers regardless of permissions).
 *
 * All methods are best-effort: on non-MEFERI firmware the service simply doesn't
 * exist and everything returns false without side effects.
 */
object MeferiSdkBridge {

    private const val TAG = "MeferiSdkBridge"
    private const val CORE_SERVICE = "MefCoreService"
    private const val PROFILE_SERVICE_DESCRIPTOR = "com.meferi.devicemanager.IProfileService"
    private const val DEVICE_MANAGER_DESCRIPTOR = "com.meferi.devicemanager.IDeviceManager"
    private const val TXN_GET_DEVICE_MANAGER_SERVICE = 3
    private const val TXN_SET_NAVIGATIONBAR_ENABLE = 5

    fun setNavigationBarEnabled(enabled: Boolean): Boolean {
        val deviceManager = getDeviceManagerBinder() ?: return false
        val data = Parcel.obtain()
        val reply = Parcel.obtain()
        return try {
            data.writeInterfaceToken(DEVICE_MANAGER_DESCRIPTOR)
            data.writeInt(if (enabled) 1 else 0)
            deviceManager.transact(TXN_SET_NAVIGATIONBAR_ENABLE, data, reply, 0)
            reply.readException()
            val ok = reply.readInt() != 0
            Log.i(TAG, "set_navigationbar_enabled value=$enabled ok=$ok")
            ok
        } catch (t: Throwable) {
            Log.w(TAG, "set_navigationbar_enabled_failed ${t.javaClass.simpleName}:${t.message.orEmpty()}")
            false
        } finally {
            data.recycle()
            reply.recycle()
        }
    }

    private fun getDeviceManagerBinder(): IBinder? {
        val core = runCatching {
            val serviceManager = Class.forName("android.os.ServiceManager")
            val getService = serviceManager.getMethod("getService", String::class.java)
            getService.invoke(null, CORE_SERVICE) as? IBinder
        }.getOrNull() ?: return null

        val data = Parcel.obtain()
        val reply = Parcel.obtain()
        return try {
            data.writeInterfaceToken(PROFILE_SERVICE_DESCRIPTOR)
            core.transact(TXN_GET_DEVICE_MANAGER_SERVICE, data, reply, 0)
            reply.readException()
            reply.readStrongBinder()
        } catch (t: Throwable) {
            Log.w(TAG, "get_device_manager_failed ${t.javaClass.simpleName}:${t.message.orEmpty()}")
            null
        } finally {
            data.recycle()
            reply.recycle()
        }
    }
}
