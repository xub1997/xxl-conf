package com.xxl.conf.core.factory;

import com.xxl.conf.core.core.XxlConfLocalCacheConf;
import com.xxl.conf.core.core.XxlConfMirrorConf;
import com.xxl.conf.core.core.XxlConfRemoteConf;
import com.xxl.conf.core.listener.XxlConfListenerFactory;
import com.xxl.conf.core.listener.impl.BeanRefreshXxlConfListener;

/**
 * XxlConf Base Factory
 *
 * @author xuxueli 2015-9-12 19:42:49
 */
public class XxlConfBaseFactory {


	/**
	 * init
	 *
	 * @param adminAddress
	 * @param env
	 */
	public static void init(String adminAddress, String env, String accessToken, String mirrorfile) {
		// init
		//初始化远程调用工具
		XxlConfRemoteConf.init(adminAddress, env, accessToken);	// init remote util
		//初始化镜像工具？？？
		XxlConfMirrorConf.init(mirrorfile);			// init mirror util
		//初始化缓存+线程，循环刷新+监控
		XxlConfLocalCacheConf.init();				// init cache + thread, cycle refresh + monitor

		//添加监听器，监听配置变化
		XxlConfListenerFactory.addListener(null, new BeanRefreshXxlConfListener());    // listener all key change

	}

	/**
	 * destory
	 * 销毁XxlConfLocalCacheConf的refreshThread守护线程
	 */
	public static void destroy() {
		XxlConfLocalCacheConf.destroy();	// destroy
	}

}
