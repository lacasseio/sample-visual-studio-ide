# Demonstrate generating Visual Studio IDE solution/vcxproj

This sample demonstrate how to generate a VS IDE solution for the core Gradle native plugins.

```
$ ./gradlew openVisualStudio
```

The integration is as follow:
- Each `CppComponent` has an associated vcxproj
- One solution is generated in the root project of the host build containing all vcxproj
- Each vcxproj are a normal VS IDE project but has the build and clean task overwritten to delegate to Gradle
- The Gradle delegation calls a bridge task that deal with the integration
- In a dual linkage project, only the shared library is exposed to VS IDE
- Each `CppComponent` expose the following to VS IDE:
  - all include paths
  - all macros
  - Windows subsystem
  - STD C++ flag

More can be done... Let's talk!