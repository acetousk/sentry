/*
 * This software is in the public domain under CC0 1.0 Universal plus a
 * Grant of Patent License.
 *
 * To the extent possible under law, the author(s) have dedicated all
 * copyright and related and neighboring rights to this software to the
 * public domain worldwide. This software is distributed without any
 * warranty.
 *
 * You should have received a copy of the CC0 Public Domain Dedication
 * along with this software (see the LICENSE.md file). If not, see
 * <http://creativecommons.org/publicdomain/zero/1.0/>.
 */
package org.moqui

import groovy.transform.CompileStatic
import io.sentry.event.Event
import org.moqui.context.ArtifactExecutionInfo
import org.moqui.context.ExecutionContextFactory
import org.moqui.context.LogEventSubscriber
import org.moqui.context.ToolFactory
import org.moqui.impl.context.ExecutionContextFactoryImpl
import org.slf4j.Logger
import org.slf4j.LoggerFactory

import io.sentry.SentryClient
import io.sentry.context.Context;
import io.sentry.event.BreadcrumbBuilder;
import io.sentry.event.UserBuilder;
import io.sentry.event.EventBuilder;
import io.sentry.Sentry
import io.sentry.event.Event.Level

import org.apache.logging.log4j.Level
import org.apache.logging.log4j.core.LogEvent
import org.apache.logging.log4j.util.ReadOnlyStringMap

@CompileStatic
final class SentryToolFactory implements ToolFactory<SentryClient> {
    protected final static Logger logger = LoggerFactory.getLogger(SentryToolFactory.class)
    final static String TOOL_NAME = "Sentry"

    protected ExecutionContextFactoryImpl ecfi = null

    private SentryClient sentry = null;
    private String sentry_dsn = null;
    private boolean disabled = false;
    protected SentrySubscriber subscriber = null;

    @Override String getName() { return TOOL_NAME }

    /**
     * Initializes a new {@code sentry}.
     */
    @Override
    void init(ExecutionContextFactory ecf) {
        this.ecfi = (ExecutionContextFactoryImpl) ecf

        sentry_dsn = System.getProperty("sentry_dsn") ?: System.getenv("sentry_dsn")

        if (!sentry_dsn) {
            logger.error("Sentry DSN not found. Set the sentry_dsn property in a MoquiConf.xml or environment variable")
            return
        }

        sentry = Sentry.init(sentry_dsn)
        subscriber = new SentrySubscriber(this)
        ecfi.registerLogEventSubscriber(subscriber)

        logger.info("Starting sentry component");
    }
    @Override void preFacadeInit(ExecutionContextFactory ecf) { }

    @Override
    SentryClient getInstance(Object... parameters) {
        if (sentry == null) throw new IllegalStateException("SentryToolFactory not initialized")
        return sentry
    }

    @Override
    void destroy() {
        if (sentry != null) try {
            Sentry.close()
            disabled = true
            logger.info("Sentry connections removed")
        } catch (Throwable t) { logger.error("Error in Sentry server stop", t) }
    }

    static class SentrySubscriber implements LogEventSubscriber {
        private final SentryToolFactory sentryTool
        private final InetAddress localAddr = InetAddress.getLocalHost()

        SentrySubscriber(SentryToolFactory sentryTool) { this.sentryTool = sentryTool }

        @Override
        void process(LogEvent event) {
            if (sentryTool.disabled) return
            // NOTE: levels configurable in log4j2.xml but always exclude these
            if (Level.INFO.is(event.level) || Level.DEBUG.is(event.level) || Level.TRACE.is(event.level)) return

            Map<String, Object> msgMap = ['@timestamp':event.timeMillis, level:event.level.toString(), thread_name:event.threadName,
                                          thread_id:event.threadId, thread_priority:event.threadPriority, logger_name:event.loggerName,
                                          message:event.message?.formattedMessage, source_host:localAddr.hostName] as Map<String, Object>
            ReadOnlyStringMap contextData = event.contextData
            if (contextData != null && contextData.size() > 0) {
                Map<String, String> mdcMap = new HashMap<>(contextData.toMap())
                String userId = mdcMap.get("moqui_userId")
                if (userId != null) { msgMap.put("user_id", userId); mdcMap.remove("moqui_userId") }
                String visitorId = mdcMap.get("moqui_visitorId")
                if (visitorId != null) { msgMap.put("visitor_id", visitorId); mdcMap.remove("moqui_visitorId") }
                if (mdcMap.size() > 0) msgMap.put("mdc", mdcMap)
                // System.out.println("Cur user ${userId} ${visitorId}")
            }
            Throwable thrown = event.thrown
//            if (thrown != null) msgMap.put("thrown", makeThrowableMap(thrown))

            sentryTool.getInstance().sendEvent(new EventBuilder().withMessage(event.message.formattedMessage)
                    .withLevel(Event.Level.valueOf(event.level.toString())).withLogger(event.loggerName)
                    .withServerName(localAddr.hostName).withTimestamp(new java.util.Date(event.timeMillis))
                    .withEnvironment(System.getProperty("instance_purpose"))
                    .withTag("thread_name", event.threadName).withTag("thread_id", event.threadId as String)
                    .withTag("thread_priority", event.threadPriority as String)
                    .withTag("user_id", msgMap.get("user_id") as String)
                    .withTag("visitor_id", msgMap.get("visitor_id") as String)

            )
        }
        static Map makeThrowableMap(Throwable thrown) {
            StackTraceElement[] stArray = thrown.stackTrace
            List<String> stList = []
            for (int i = 0; i < stArray.length; i++) {
                StackTraceElement ste = (StackTraceElement) stArray[i]
                stList.add("${ste.className}.${ste.methodName}(${ste.fileName}:${ste.lineNumber})".toString())
            }
            Map<String, Object> thrownMap = [name:thrown.class.name, message:thrown.message,
                                             localizedMessage:thrown.localizedMessage, stackTrace:stList] as Map<String, Object>
            if (thrown instanceof BaseArtifactException) {
                BaseArtifactException bae = (BaseArtifactException) thrown
                Deque<ArtifactExecutionInfo> aeiList = bae.getArtifactStack()
                if (aeiList != null && aeiList.size() > 0) thrownMap.put("artifactStack", aeiList.collect({ it.toBasicString() }))
            }
            Throwable cause = thrown.cause
            if (cause != null) thrownMap.put("cause", makeThrowableMap(cause))
            Throwable[] supArray = thrown.suppressed
            if (supArray != null && supArray.length > 0) {
                List<Map> supList = []
                for (int i = 0; i < supArray.length; i++) {
                    Throwable sup = supArray[i]
                    supList.add(makeThrowableMap(sup))
                }
                thrownMap.put("suppressed", supList)
            }
            return thrownMap
        }
    }

}

