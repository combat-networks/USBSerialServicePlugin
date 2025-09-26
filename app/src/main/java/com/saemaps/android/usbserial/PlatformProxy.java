package com.saemaps.android.usbserial;

import com.saemaps.android.usbserial.PluginComponent;

import java.lang.reflect.Method;
import java.util.LinkedList;
import java.util.List;

// 平台代理应当采用单例模式，因为平台只有一个
public class PlatformProxy {

    private static PlatformProxy instance;
    private static Class<?> clazz;
    private  ClassLoader platformClassLoader;
    private  Object platformComp;

    private PlatformProxy(Class<?> clazz) {
        PlatformProxy.clazz = clazz;
        platformClassLoader = clazz.getClassLoader();
        try {
            // 取得平台的组件实例
            platformComp = clazz.getMethod("getInstance").invoke(null);
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    public boolean isInit(){
        return instance != null;
    }

    public static PlatformProxy getInstance() {
        return getInstance(null);
    }

    public static synchronized PlatformProxy getInstance(Class<?> clazz) {
        if (clazz != null && (instance == null || PlatformProxy.clazz != clazz)) {
            instance = new PlatformProxy(clazz);
        }
        return instance;
    }

    // 调用当前关联插件提供的get方法，关联关系要在平台中配置
    public <T> T getFromContext(String name, Class<T> type, Object... params) {
        try {
            Method method = clazz.getMethod("getFromContext", Class.class, String.class, Class.class, Object[].class);
            return (T) method.invoke(platformComp, PluginComponent.class, name, type, params);
        } catch (Exception e) {
            e.printStackTrace();
        }
        return null;
    }

    public <T> T get(String name, Class<T> type, Object... params) {
        List<Class<?>> paramTypes = new LinkedList<>();
        for (Object param : params) {
            paramTypes.add(param.getClass());
        }
        Class<?>[] objects = paramTypes.toArray(new Class[0]);
        try {
            Method method = clazz.getMethod("get" + name, objects);
            return (T) method.invoke(platformComp, params);
        } catch (Exception e) {
            e.printStackTrace();
        }
        return null;
    }

    public void set(String name, Object... params) {
        List<Class<?>> paramTypes = new LinkedList<>();
        for (Object param : params) {
            paramTypes.add(param.getClass());
        }
        Class<?>[] objects = paramTypes.toArray(new Class[0]);
        try {
            Method method = clazz.getMethod("set" + name, objects);
            method.invoke(platformComp, params);
        } catch (Exception e) {
            e.printStackTrace();
        }
    }
}



