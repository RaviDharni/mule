/*
 * Copyright (c) MuleSoft, Inc.  All rights reserved.  http://www.mulesoft.com
 * The software in this package is published under the terms of the CPAL v1.0
 * license, a copy of which has been included with this distribution in the
 * LICENSE.txt file.
 */
package org.mule.runtime.module.extension.internal.runtime.config;

import static java.lang.Boolean.valueOf;
import static java.lang.Integer.getInteger;
import static java.lang.String.format;
import static java.util.concurrent.TimeUnit.MILLISECONDS;
import static org.mule.runtime.api.i18n.I18nMessageFactory.createStaticMessage;
import static org.mule.runtime.api.util.MuleSystemProperties.ASYNC_TEST_CONNECTIVITY_TIMEOUT_PROPERTY;
import static org.mule.runtime.api.util.Preconditions.checkState;
import static org.mule.runtime.core.api.config.MuleDeploymentProperties.MULE_LAZY_INIT_DEPLOYMENT_PROPERTY;
import static org.mule.runtime.core.api.lifecycle.LifecycleUtils.disposeIfNeeded;
import static org.mule.runtime.core.api.lifecycle.LifecycleUtils.initialiseIfNeeded;
import static org.mule.runtime.core.api.lifecycle.LifecycleUtils.startIfNeeded;
import static org.mule.runtime.core.api.lifecycle.LifecycleUtils.stopIfNeeded;
import static org.mule.runtime.core.internal.config.ConfigurationInstanceNotification.CONFIGURATION_STOPPED;
import static org.slf4j.LoggerFactory.getLogger;

import org.mule.runtime.api.component.ConfigurationProperties;
import org.mule.runtime.api.connection.ConnectionException;
import org.mule.runtime.api.connection.ConnectionProvider;
import org.mule.runtime.api.connection.ConnectionValidationResult;
import org.mule.runtime.api.exception.DefaultMuleException;
import org.mule.runtime.api.exception.MuleException;
import org.mule.runtime.api.lifecycle.InitialisationException;
import org.mule.runtime.api.lifecycle.Lifecycle;
import org.mule.runtime.api.lock.LockFactory;
import org.mule.runtime.api.meta.model.config.ConfigurationModel;
import org.mule.runtime.api.notification.NotificationDispatcher;
import org.mule.runtime.api.scheduler.Scheduler;
import org.mule.runtime.api.scheduler.SchedulerService;
import org.mule.runtime.api.time.TimeSupplier;
import org.mule.runtime.api.util.LazyValue;
import org.mule.runtime.api.util.concurrent.Latch;
import org.mule.runtime.core.api.MuleContext;
import org.mule.runtime.core.api.connector.ConnectionManager;
import org.mule.runtime.core.api.retry.RetryCallback;
import org.mule.runtime.core.api.retry.RetryContext;
import org.mule.runtime.core.api.retry.policy.RetryPolicyTemplate;
import org.mule.runtime.core.internal.config.ConfigurationInstanceNotification;
import org.mule.runtime.core.internal.connection.ConnectionManagerAdapter;
import org.mule.runtime.core.internal.retry.ReconnectionConfig;
import org.mule.runtime.core.internal.time.LocalTimeSupplier;
import org.mule.runtime.extension.api.connectivity.NoConnectivityTest;
import org.mule.runtime.extension.api.runtime.config.ConfigurationInstance;
import org.mule.runtime.extension.api.runtime.config.ConfigurationState;
import org.mule.runtime.extension.api.runtime.config.ConfigurationStats;

import java.util.Optional;
import java.util.concurrent.locks.Lock;

import javax.inject.Inject;

import org.slf4j.Logger;

/**
 * Implementation of {@link ConfigurationInstance} which propagates dependency injection and lifecycle phases into the contained
 * configuration {@link #value} and {@link #connectionProvider} (if present).
 * <p>
 * In the case of the {@link #connectionProvider} being present, then it also binds the {@link #value} to the
 * {@link ConnectionProvider} by the means of {@link ConnectionManager#bind(Object, ConnectionProvider)} when the
 * {@link #initialise()} phase is executed. That bound will be broken on the {@link #stop()} phase by using
 * {@link ConnectionManager#unbind(Object)}
 *
 * @since 4.0
 */
public final class LifecycleAwareConfigurationInstance implements ConfigurationInstance, Lifecycle {

  private static final Integer DEFAULT_ASYNC_TEST_CONNECTIVITY_TIMEOUT = 30000;

  private static final int ASYNC_TEST_CONNECTIVITY_TIMEOUT =
      getInteger(ASYNC_TEST_CONNECTIVITY_TIMEOUT_PROPERTY, DEFAULT_ASYNC_TEST_CONNECTIVITY_TIMEOUT);

  private static final Logger LOGGER = getLogger(LifecycleAwareConfigurationInstance.class);
  private static final String DO_TEST_CONNECTIVITY_PROPERTY_NAME = "doTestConnectivity";

  private final String name;
  private final ConfigurationModel model;
  private final Object value;
  private final ConfigurationState configurationState;
  private final Optional<ConnectionProvider> connectionProvider;

  private ConfigurationStats configurationStats;

  @Inject
  private TimeSupplier timeSupplier;

  @Inject
  private LockFactory lockFactory;

  @Inject
  private SchedulerService schedulerService;

  @Inject
  private NotificationDispatcher notificationFirer;

  @Inject
  private ConnectionManagerAdapter connectionManager;

  @Inject
  private ConfigurationProperties configurationProperties;

  @Inject
  private MuleContext muleContext;

  private volatile Lock testConnectivityLock;

  private volatile boolean initialized = false;
  private volatile boolean started = false;

  private LazyValue<Boolean> doTestConnectivity = new LazyValue<>(this::getDoTestConnectivityProperty);

  /**
   * Creates a new instance
   *
   * @param name this configuration's name
   * @param model the {@link ConfigurationModel} for this instance
   * @param value the actual configuration instance
   * @param connectionProvider an {@link Optional} containing the {@link ConnectionProvider} to use
   */
  public LifecycleAwareConfigurationInstance(String name,
                                             ConfigurationModel model,
                                             Object value,
                                             ConfigurationState configurationState,
                                             Optional<ConnectionProvider> connectionProvider) {
    this.name = name;
    this.model = model;
    this.value = value;
    this.configurationState = configurationState;
    this.connectionProvider = connectionProvider;
  }

  @Override
  public synchronized void initialise() throws InitialisationException {
    if (!initialized) {
      initialized = true;
      testConnectivityLock = lockFactory.createLock(this.getClass().getName() + "-testConnectivity-" + getName());
      try {
        initStats();
        doInitialise();
      } catch (Exception e) {
        if (e instanceof InitialisationException) {
          throw (InitialisationException) e;
        } else {
          throw new InitialisationException(e, this);
        }
      }
    }
  }

  @Override
  public synchronized void start() throws MuleException {
    if (!started) {
      started = true;
      if (connectionProvider.isPresent()) {
        startIfNeeded(connectionProvider);
        if (!connectionManager.hasBinding(value)) {
          connectionManager.bind(value, connectionProvider.get());
        }
        if (doTestConnectivity.get()) {
          testConnectivity();
        }
      }
      startIfNeeded(value);
    }
  }

  private void testConnectivity() throws MuleException {
    ConnectionProvider provider = connectionProvider.get();
    if (provider instanceof NoConnectivityTest) {
      return;
    }

    Scheduler retryScheduler = schedulerService.ioScheduler();
    RetryPolicyTemplate retryTemplate = connectionManager.getRetryTemplateFor(provider);
    ReconnectionConfig reconnectionConfig = connectionManager.getReconnectionConfigFor(provider);
    final Latch latch = new Latch();
    RetryCallback retryCallback = new RetryCallback() {

      @Override
      public void doWork(RetryContext context) throws Exception {
        try {
          Lock lock = testConnectivityLock;
          if (lock != null) {
            final boolean lockAcquired = lock.tryLock();
            if (lockAcquired) {
              LOGGER.debug("Doing testConnectivity() for config '{}'", getName());
              try {
                ConnectionValidationResult result = connectionManager.testConnectivity(LifecycleAwareConfigurationInstance.this);
                if (result.isValid()) {
                  context.setOk();
                } else {
                  if ((reconnectionConfig.isFailsDeployment())) {
                    context.setFailed(result.getException());
                    throw new ConnectionException(format("Connectivity test failed for config '%s'", getName()),
                                                  result.getException());
                  } else {
                    if (LOGGER.isInfoEnabled()) {
                      LOGGER
                          .info(format("Connectivity test failed for config '%s'. Application deployment will continue. Error was: %s",
                                       getName(), result.getMessage()),
                                result.getException());
                    }
                  }
                }
              } finally {
                lock.unlock();
              }
            } else {
              LOGGER.warn("There is a testConnectivity() already running for config '{}'", getName());
            }
          }
        } finally {
          latch.countDown();
        }
      }

      @Override
      public String getWorkDescription() {
        return format("Testing connectivity for config '%s'", getName());
      }

      @Override
      public Object getWorkOwner() {
        return value;
      }
    };

    try {
      retryTemplate.execute(retryCallback, retryScheduler);
    } catch (Exception e) {
      throw new DefaultMuleException(createStaticMessage(format("Could not perform connectivity testing for config '%s'",
                                                                getName())),
                                     e);
    } finally {
      if (retryTemplate.isAsync()) {
        try {
          latch.await(ASYNC_TEST_CONNECTIVITY_TIMEOUT, MILLISECONDS);
        } catch (InterruptedException e) {
          LOGGER.warn("InterruptedException while waiting for the test connectivity to finish", e);
        }
      }
      if (retryScheduler != null) {
        retryScheduler.stop();
      }
    }
  }

  /**
   * Propagates this lifecycle phase into the the {@link #value}. Also triggers a
   * {@link ConfigurationInstanceNotification} that is being stopped.
   *
   * @throws MuleException if an exception is found
   */
  @Override
  public synchronized void stop() throws MuleException {
    if (started) {
      started = false;
      try {
        stopIfNeeded(value);
        if (connectionProvider.isPresent()) {
          testConnectivityLock.lock();
          try {
            connectionManager.unbind(value);
            stopIfNeeded(connectionProvider);
          } finally {
            testConnectivityLock.unlock();
          }
        }
      } finally {
        notificationFirer.dispatch(new ConfigurationInstanceNotification(this, CONFIGURATION_STOPPED));
      }
    }
  }

  /**
   * Propagates this lifecycle phase into the the {@link #value}.
   */
  @Override
  public synchronized void dispose() {
    if (initialized) {
      initialized = false;
      disposeIfNeeded(value, LOGGER);
      disposeIfNeeded(connectionProvider, LOGGER);
      configurationStats = null;
      testConnectivityLock = null;
    }
  }

  private void doInitialise() throws InitialisationException {
    if (connectionProvider.isPresent()) {
      initialiseIfNeeded(connectionProvider, true, muleContext);
      connectionManager.bind(value, connectionProvider.get());
    }

    initialiseIfNeeded(value, true, muleContext);
  }

  /**
   * {@inheritDoc}
   */
  @Override
  public String getName() {
    return name;
  }

  /**
   * {@inheritDoc}
   */
  @Override
  public Optional<ConnectionProvider> getConnectionProvider() {
    return connectionProvider;
  }

  /**
   * {@inheritDoc}
   */
  @Override
  public ConfigurationModel getModel() {
    return model;
  }

  /**
   * {@inheritDoc}
   */
  @Override
  public Object getValue() {
    return value;
  }

  /**
   * {@inheritDoc}
   *
   * @throws IllegalStateException if invoked before {@link #initialise()}
   */
  @Override
  public ConfigurationStats getStatistics() {
    checkState(configurationStats != null, "can't get statistics before initialise() is invoked");
    return configurationStats;
  }

  @Override
  public ConfigurationState getState() {
    return configurationState;
  }

  private void initStats() {
    if (timeSupplier == null) {
      timeSupplier = new LocalTimeSupplier();
    }

    configurationStats = new DefaultMutableConfigurationStats(timeSupplier);
  }

  private boolean getDoTestConnectivityProperty() {
    return System.getProperty(DO_TEST_CONNECTIVITY_PROPERTY_NAME) != null
        ? valueOf(System.getProperty(DO_TEST_CONNECTIVITY_PROPERTY_NAME))
        : !configurationProperties.resolveBooleanProperty(MULE_LAZY_INIT_DEPLOYMENT_PROPERTY).orElse(false);
  }
}
