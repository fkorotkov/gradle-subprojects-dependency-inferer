# gradle-subprojects-dependency-inferer

Infer dependencies between sub-projects. Works only with `build.gradle` files and infers the deps based on simple parsing
of Koltin files. Generation is done via IntelliJ SDK which allows to create an in-memory fake projects which allows
to easily get ASTs of kotlin files to figure out inheritance chain and return typoes of all the medots.

To run:

Modify `AppTest.kt` file with a path to your project and run `./gradlew test`.
