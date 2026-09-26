/*
 * This file is auto-generated.  DO NOT MODIFY.
 * Using: C:\Users\Administrator\AppData\Local\Android\Sdk\build-tools\35.0.0\aidl.exe -pC:\Users\Administrator\AppData\Local\Android\Sdk\platforms\android-34\framework.aidl -ID:\pojie应用\biancheng\work\aidl -oD:\pojie应用\biancheng\amap-enhancer\module\src\io\github\libxposed\service D:\pojie应用\biancheng\work\aidl\io\github\libxposed\service\IHotReloadCallback.aidl
 */
package io.github.libxposed.service;
/** Callback for asynchronous hot reload completion. */
public interface IHotReloadCallback extends android.os.IInterface
{
  /** Default implementation for IHotReloadCallback. */
  public static class Default implements io.github.libxposed.service.IHotReloadCallback
  {
    /**
     * Called when hot reload completes or fails.
     * 
     * @param status The raw hot reload status
     * @param message Optional diagnostic message; null for module-refused reloads
     */
    @Override public void onHotReloadResult(int status, java.lang.String message) throws android.os.RemoteException
    {
    }
    @Override
    public android.os.IBinder asBinder() {
      return null;
    }
  }
  /** Local-side IPC implementation stub class. */
  public static abstract class Stub extends android.os.Binder implements io.github.libxposed.service.IHotReloadCallback
  {
    /** Construct the stub at attach it to the interface. */
    @SuppressWarnings("this-escape")
    public Stub()
    {
      this.attachInterface(this, DESCRIPTOR);
    }
    /**
     * Cast an IBinder object into an io.github.libxposed.service.IHotReloadCallback interface,
     * generating a proxy if needed.
     */
    public static io.github.libxposed.service.IHotReloadCallback asInterface(android.os.IBinder obj)
    {
      if ((obj==null)) {
        return null;
      }
      android.os.IInterface iin = obj.queryLocalInterface(DESCRIPTOR);
      if (((iin!=null)&&(iin instanceof io.github.libxposed.service.IHotReloadCallback))) {
        return ((io.github.libxposed.service.IHotReloadCallback)iin);
      }
      return new io.github.libxposed.service.IHotReloadCallback.Stub.Proxy(obj);
    }
    @Override public android.os.IBinder asBinder()
    {
      return this;
    }
    @Override public boolean onTransact(int code, android.os.Parcel data, android.os.Parcel reply, int flags) throws android.os.RemoteException
    {
      java.lang.String descriptor = DESCRIPTOR;
      if (code >= android.os.IBinder.FIRST_CALL_TRANSACTION && code <= android.os.IBinder.LAST_CALL_TRANSACTION) {
        data.enforceInterface(descriptor);
      }
      if (code == INTERFACE_TRANSACTION) {
        reply.writeString(descriptor);
        return true;
      }
      switch (code)
      {
        case TRANSACTION_onHotReloadResult:
        {
          int _arg0;
          _arg0 = data.readInt();
          java.lang.String _arg1;
          _arg1 = data.readString();
          this.onHotReloadResult(_arg0, _arg1);
          break;
        }
        default:
        {
          return super.onTransact(code, data, reply, flags);
        }
      }
      return true;
    }
    private static class Proxy implements io.github.libxposed.service.IHotReloadCallback
    {
      private android.os.IBinder mRemote;
      Proxy(android.os.IBinder remote)
      {
        mRemote = remote;
      }
      @Override public android.os.IBinder asBinder()
      {
        return mRemote;
      }
      public java.lang.String getInterfaceDescriptor()
      {
        return DESCRIPTOR;
      }
      /**
       * Called when hot reload completes or fails.
       * 
       * @param status The raw hot reload status
       * @param message Optional diagnostic message; null for module-refused reloads
       */
      @Override public void onHotReloadResult(int status, java.lang.String message) throws android.os.RemoteException
      {
        android.os.Parcel _data = android.os.Parcel.obtain();
        try {
          _data.writeInterfaceToken(DESCRIPTOR);
          _data.writeInt(status);
          _data.writeString(message);
          boolean _status = mRemote.transact(Stub.TRANSACTION_onHotReloadResult, _data, null, android.os.IBinder.FLAG_ONEWAY);
        }
        finally {
          _data.recycle();
        }
      }
    }
    static final int TRANSACTION_onHotReloadResult = (android.os.IBinder.FIRST_CALL_TRANSACTION + 1);
  }
  /** @hide */
  public static final java.lang.String DESCRIPTOR = "io.github.libxposed.service.IHotReloadCallback";
  /**
   * Called when hot reload completes or fails.
   * 
   * @param status The raw hot reload status
   * @param message Optional diagnostic message; null for module-refused reloads
   */
  public void onHotReloadResult(int status, java.lang.String message) throws android.os.RemoteException;
}
