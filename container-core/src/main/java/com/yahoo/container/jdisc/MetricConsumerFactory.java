// Copyright 2017 Yahoo Holdings. Licensed under the terms of the Apache 2.0 license. See LICENSE in the project root.
package com.yahoo.container.jdisc;

import com.yahoo.jdisc.application.MetricConsumer;

/**
 * <p>This is the interface to implement if one wishes to configure a non-default <code>MetricConsumer</code>. Simply
 * add the implementing class as a component in your services.xml file.</p>
 *
 * @author Simon Thoresen Hult
 */
public interface MetricConsumerFactory {

    MetricConsumer newInstance();

}
