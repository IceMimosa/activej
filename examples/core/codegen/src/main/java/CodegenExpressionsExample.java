import io.activej.codegen.ClassBuilder;
import io.activej.codegen.DefiningClassLoader;

import static io.activej.codegen.expression.Expressions.*;

public final class CodegenExpressionsExample {
	public static void main(String[] args) throws ReflectiveOperationException {
		//[START REGION_1]
		Class<Example> example = ClassBuilder
				// DefiningClassLoader represents a loader for defining dynamically generated classes
				.create(DefiningClassLoader.create(Thread.currentThread().getContextClassLoader()), Example.class)
				.withMethod("sayHello", call(staticField(System.class, "out"), "println", value("Hello world")))
				.build();
		//[END REGION_1]

		//[START REGION_2]
		Example instance = example.getDeclaredConstructor().newInstance();
		instance.sayHello();
		//[END REGION_2]
	}

	//[START REGION_3]
	public interface Example {
		void sayHello();
	}
	//[END REGION_3]
}
