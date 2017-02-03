## Step Builder Generator Plugin for [JetBrains IntelliJ IDEA](https://www.jetbrains.com/idea/)

### What is it?
Based on the Builder creational design pattern, this plugin generates an internal Step Builder for a class. A Step Builder guides the users of your class through the creation process without creating objects with inconsistent internal state.

### Where do I find the plugin when it is installed?
You can find an additional action in the "Generate..." menu.

### How do I start?
Implement the easiest version of your class, e.g.:
```java
public class Person {
    protected final String firstName;
    protected final String lastName;
    protected int age;
    protected boolean active;
}
```

Even though this class will not compile as is (final fields need to be initialized at once or in constructors),
it is a good starting point for the Step Builder. The plugin uses all final fields as mandatory fields, creating
explicit Step interfaces for them. All non-final fields are considered optional. A private constructor having
parameters for all final fields is created (all other constructors will be removed), as well as getters for all fields.
The starting point, a static method called "newInstance", is added to the class.

### What's the benefit for the users of my class?
Users of your class will leverage the Step Builder whenever they need to create new instances. Example:
```java
Person p = Person.newInstance()
                 .firstName("John")
                 .lastName("Doe")
                 .age(27)
                 .active(true)
                 .build();
```

### Additional resources about the Builder pattern and the Step Builder:
[Crisp's Blog - Another builder pattern for Java](http://blog.crisp.se/2013/10/09/perlundholm/another-builder-pattern-for-java)
[Remove duplications and fix bad names: Step Builder pattern](http://rdafbn.blogspot.ie/2012/07/step-builder-pattern_28.html)
