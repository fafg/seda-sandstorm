/* 
 * Copyright (c) 2000 by Matt Welsh and The Regents of the University of 
 * California. All rights reserved.
 *
 * Permission to use, copy, modify, and distribute this software and its
 * documentation for any purpose, without fee, and without written agreement is
 * hereby granted, provided that the above copyright notice and the following
 * two paragraphs appear in all copies of this software.
 * 
 * IN NO EVENT SHALL THE UNIVERSITY OF CALIFORNIA BE LIABLE TO ANY PARTY FOR
 * DIRECT, INDIRECT, SPECIAL, INCIDENTAL, OR CONSEQUENTIAL DAMAGES ARISING OUT
 * OF THE USE OF THIS SOFTWARE AND ITS DOCUMENTATION, EVEN IF THE UNIVERSITY OF
 * CALIFORNIA HAS BEEN ADVISED OF THE POSSIBILITY OF SUCH DAMAGE.
 * 
 * THE UNIVERSITY OF CALIFORNIA SPECIFICALLY DISCLAIMS ANY WARRANTIES,
 * INCLUDING, BUT NOT LIMITED TO, THE IMPLIED WARRANTIES OF MERCHANTABILITY
 * AND FITNESS FOR A PARTICULAR PURPOSE.  THE SOFTWARE PROVIDED HEREUNDER IS
 * ON AN "AS IS" BASIS, AND THE UNIVERSITY OF CALIFORNIA HAS NO OBLIGATION TO
 * PROVIDE MAINTENANCE, SUPPORT, UPDATES, ENHANCEMENTS, OR MODIFICATIONS.
 *
 * Author: Matt Welsh <mdw@cs.berkeley.edu>
 * 
 */

package seda.sandStorm.lib.aSocket;

import seda.sandStorm.api.*;
import seda.sandStorm.api.internal.*;
import seda.sandStorm.core.*;
import seda.sandStorm.internal.*;
import seda.sandStorm.main.*;

import java.net.*;
import java.io.*;
import java.util.*;

/**
 * The aSocketMgr is an internal class used to provide an interface between the
 * Sandstorm runtime and the aSocket library. Applications should not make use
 * of this class.
 * 
 * @author Matt Welsh
 */
public class aSocketMgr {

	private static final boolean DEBUG = false;

	/**
	 * aSocketTM和aSocketRCTM是由global.aSocket.governor.enable决定的
	 * 两个变量都在initialize方法中赋值。
	 */
	private static ThreadManagerIF aSocketTM, aSocketRCTM;
	/**
	 * readstage的sink（队列）
	 */
	private static SinkIF read_sink;
	private static SinkIF listen_sink;
	private static SinkIF write_sink;

	/**
	 * 用作synchronized
	 */
	private static Object init_lock = new Object();
	/**
	 * 当initialize结束后赋值为true
	 */
	private static boolean initialized = false;

	static boolean USE_NIO = false;
	/**
	 * 根据USE_NIO返回对应工厂类，创建的是***state类
	 */
	//STEPIN state对象是干嘛的。
	private static aSocketImplFactory factory;

	/**
	 * Called at startup time by the Sandstorm runtime.
	 * 注意是静态方法，设置类静态变量
	 */
	public static void initialize(ManagerIF mgr, SystemManagerIF sysmgr) throws Exception {
		//!!!!Remove this after analysis.
		//!!!!请将下一行移除。
		synchronized (init_lock) {
			SandstormConfig cfg = mgr.getConfig();

			String provider = cfg.getString("global.aSocket.provider");
			if (provider == null) {
				throw new RuntimeException("aSocketMgr: Must specify either " + "'NIO' or 'NBIO' for global.aSocket.provider");
			}
			//!!!!目前global.aSocket.provider只支持NIO和NBIO两个选项，分别是干嘛的？
			if (provider.equals("NIO")) {
				USE_NIO = true;
				System.err.println("aSocket layer using JDK1.4 java.nio package");
			} else if (provider.equals("NBIO")) {
				USE_NIO = false;
				System.err.println("aSocket layer using NBIO package");
			} else {
				throw new RuntimeException("aSocketMgr: Must specify either " + "'NIO' or 'NBIO' for global.aSocket.provider");
			}

			try {
				//!!!!simple factory pattern.
				//!!!!返回一个工厂类，根据是否使用NBIO
				factory = aSocketImplFactory.getFactory();
			} catch (Exception e) {
				throw new RuntimeException("aSocketMgr: Cannot create aSocketImplFactory: " + e);
			}

			aSocketTM = new aSocketThreadManager(mgr);
			//将SocketTM加入sandstormMgr中的defaulttm表中。aSocketTM是干嘛的？TM是干嘛的？
			sysmgr.addThreadManager("aSocket", aSocketTM);
			//创建ReadStageWrapper
			ReadEventHandler revh = new ReadEventHandler();
			aSocketStageWrapper rsw;
			if (cfg.getBoolean("global.aSocket.governor.enable")) {
				aSocketRCTM = new aSocketRCTMSleep(mgr);
				sysmgr.addThreadManager("aSocketRCTM", aSocketRCTM);
				//!!!!此调用是关键，初始化一个StageWrapper，怎么用?
				//STEPIN 还不知道怎么用wrapper
				rsw = new aSocketStageWrapper("aSocket ReadStage", revh, new ConfigData(mgr), aSocketRCTM);
			} else {
				rsw = new aSocketStageWrapper("aSocket ReadStage", revh, new ConfigData(mgr), aSocketTM);
			}
			//!!!!将rsw wrapper注册到sysmgr，此处是sandStormMgr中，并返回wrapper中的stage。wrapper中的stage是在上一行new的时候创造的。
			StageIF readStage = sysmgr.createStage(rsw, true);
			//STEPIN 跟踪getSink，sink和EventQueue的不同
			//STEPIN 为什么要记录sink，AFileMgr就没有
			read_sink = readStage.getSink();
			//创建ListenStageWrapper
			ListenEventHandler levh = new ListenEventHandler();
			aSocketStageWrapper lsw = new aSocketStageWrapper("aSocket ListenStage", levh, new ConfigData(mgr), aSocketTM);
			StageIF listenStage = sysmgr.createStage(lsw, true);
			listen_sink = listenStage.getSink();
			//创建WriteEventHandler
			WriteEventHandler wevh = new WriteEventHandler();
			aSocketStageWrapper wsw = new aSocketStageWrapper("aSocket WriteStage", wevh, new ConfigData(mgr), aSocketTM);
			StageIF writeStage = sysmgr.createStage(wsw, true);
			write_sink = writeStage.getSink();
			//涉及到sandstormmgr的有stagewrapper都被注册到stagetbl成员变量中。TM注册到tmtbl
			//另外TM和SW都有mgr引用
			initialized = true;
		}
	}

	/**
	 * Ensure that the aSocket layer is initialized, in case the library is
	 * being used in standalone mode.
	 */
	static void init() {
		synchronized (init_lock) {
			// When invoked in standalone mode
			if (!initialized) {
				try {
					Sandstorm ss = Sandstorm.getSandstorm();
					if (ss != null) {
						initialize(ss.getManager(), ss.getSystemManager());
					} else {
						SandstormConfig cfg = new SandstormConfig();
						ss = new Sandstorm(cfg);
					}
				} catch (Exception e) {
					System.err.println("aSocketMgr: Warning: Initialization failed: " + e);
					e.printStackTrace();
					return;
				}
			}
		}
	}

	static aSocketImplFactory getFactory() {
		return factory;
	}

	static public void enqueueRequest(aSocketRequest req) {
		init();

		if ((req instanceof ATcpWriteRequest) || (req instanceof ATcpConnectRequest) || (req instanceof ATcpFlushRequest) || (req instanceof ATcpCloseRequest)
				|| (req instanceof AUdpWriteRequest) || (req instanceof AUdpCloseRequest) || (req instanceof AUdpFlushRequest) || (req instanceof AUdpConnectRequest)
				|| (req instanceof AUdpDisconnectRequest)) {

			try {
				write_sink.enqueue(req);
			} catch (SinkException se) {
				System.err.println("aSocketMgr.enqueueRequest: Warning: Got SinkException " + se);
				System.err.println("aSocketMgr.enqueueRequest: This is a bug - contact <mdw@cs.berkeley.edu>");
			}

		} else if ((req instanceof ATcpStartReadRequest) || (req instanceof AUdpStartReadRequest)) {

			try {
				read_sink.enqueue(req);
			} catch (SinkException se) {
				System.err.println("aSocketMgr.enqueueRequest: Warning: Got SinkException " + se);
				System.err.println("aSocketMgr.enqueueRequest: This is a bug - contact <mdw@cs.berkeley.edu>");
			}

		} else if ((req instanceof ATcpListenRequest) || (req instanceof ATcpSuspendAcceptRequest) || (req instanceof ATcpResumeAcceptRequest)
				|| (req instanceof ATcpCloseServerRequest)) {

			try {
				listen_sink.enqueue(req);
			} catch (SinkException se) {
				System.err.println("aSocketMgr.enqueueRequest: Warning: Got SinkException " + se);
				System.err.println("aSocketMgr.enqueueRequest: This is a bug - contact <mdw@cs.berkeley.edu>");
			}

		} else {
			throw new IllegalArgumentException("Bad request type " + req);
		}
	}
}
