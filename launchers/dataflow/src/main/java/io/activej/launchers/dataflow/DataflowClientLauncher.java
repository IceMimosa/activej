/*
 * Copyright (C) 2020 ActiveJ LLC.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package io.activej.launchers.dataflow;

import io.activej.codec.StructuredCodec;
import io.activej.config.Config;
import io.activej.config.ConfigModule;
import io.activej.csp.binary.ByteBufsCodec;
import io.activej.dataflow.DataflowClient;
import io.activej.dataflow.command.DataflowCommand;
import io.activej.dataflow.command.DataflowResponse;
import io.activej.dataflow.graph.DataflowGraph;
import io.activej.dataflow.graph.Partition;
import io.activej.dataflow.inject.BinarySerializerModule.BinarySerializerLocator;
import io.activej.dataflow.inject.CodecsModule.Subtypes;
import io.activej.dataflow.inject.DataflowModule;
import io.activej.dataflow.node.Node;
import io.activej.eventloop.Eventloop;
import io.activej.eventloop.inspector.ThrottlingController;
import io.activej.inject.annotation.Optional;
import io.activej.inject.annotation.Provides;
import io.activej.inject.module.Module;
import io.activej.jmx.JmxModule;
import io.activej.launcher.Launcher;
import io.activej.service.ServiceGraphModule;

import java.util.concurrent.Executor;

import static io.activej.config.converter.ConfigConverters.*;
import static io.activej.inject.module.Modules.combine;
import static io.activej.launchers.initializers.Initializers.ofEventloop;
import static java.util.stream.Collectors.toList;

public abstract class DataflowClientLauncher extends Launcher {
	public static final String PROPERTIES_FILE = "dataflow-client.properties";
	public static final String BUSINESS_MODULE_PROP = "businessLogicModule";

	@Provides
	Eventloop eventloop(Config config, @Optional ThrottlingController throttlingController) {
		return Eventloop.create()
				.withInitializer(ofEventloop(config.getChild("eventloop")))
				.withInitializer(eventloop -> eventloop.withInspector(throttlingController));
	}

	@Provides
	Executor executor(Config config) {
		return getExecutor(config);
	}

	@Provides
	DataflowClient client(Executor executor, Config config, ByteBufsCodec<DataflowResponse, DataflowCommand> codec, BinarySerializerLocator serializers) {
		return DataflowClient.create(executor, config.get(ofPath(), "dataflow.secondaryBufferPath"), codec, serializers);
	}

	@Provides
	DataflowGraph graph(Config config, DataflowClient client, @Subtypes StructuredCodec<Node> nodeCodec) {
		return new DataflowGraph(client,
				config.get(ofList(ofInetSocketAddress()), "dataflow.partitions").stream().map(Partition::new).collect(toList()),
				nodeCodec);
	}

	@Provides
	Config config() {
		return Config.create()
				.overrideWith(Config.ofClassPathProperties(PROPERTIES_FILE, true))
				.overrideWith(Config.ofProperties(System.getProperties()).getChild("config"));
	}

	@Override
	protected final Module getModule() {
		return combine(
				ServiceGraphModule.create(),
				JmxModule.create(),
				DataflowModule.create(),
				ConfigModule.create()
						.withEffectiveConfigLogger(),
				getBusinessLogicModule()
		);
	}

	/**
	 * Override this method to supply your launcher business logic.
	 */
	protected Module getBusinessLogicModule() {
		return Module.empty();
	}

	@Override
	protected void run() throws Exception {
		awaitShutdown();
	}

	public static void main(String[] args) throws Exception {
		String businessLogicModuleName = System.getProperty(BUSINESS_MODULE_PROP);

		Module businessLogicModule = businessLogicModuleName != null ?
				(Module) Class.forName(businessLogicModuleName).newInstance() :
				Module.empty();

		Launcher launcher = new DataflowClientLauncher() {
			@Override
			protected Module getBusinessLogicModule() {
				return businessLogicModule;
			}
		};

		launcher.launch(args);
	}
}
