package dev.nokee.samples;

import dev.nokee.ide.visualstudio.VisualStudioIdeConfiguration;
import dev.nokee.ide.visualstudio.VisualStudioIdePlatform;
import dev.nokee.ide.visualstudio.VisualStudioIdePlatforms;
import dev.nokee.ide.visualstudio.VisualStudioIdeProjectConfiguration;
import dev.nokee.ide.visualstudio.VisualStudioIdeProjectExtension;
import dev.nokee.ide.visualstudio.internal.DefaultVisualStudioIdeProject;
import dev.nokee.ide.visualstudio.internal.DefaultVisualStudioIdeTarget;
import org.gradle.api.Action;
import org.gradle.api.Plugin;
import org.gradle.api.Project;
import org.gradle.api.Transformer;
import org.gradle.api.artifacts.ConfigurationContainer;
import org.gradle.api.file.FileCollection;
import org.gradle.api.file.ProjectLayout;
import org.gradle.api.file.RegularFile;
import org.gradle.api.model.ObjectFactory;
import org.gradle.api.plugins.ExtensionAware;
import org.gradle.api.provider.Provider;
import org.gradle.api.provider.ProviderFactory;
import org.gradle.api.tasks.Copy;
import org.gradle.language.cpp.CppApplication;
import org.gradle.language.cpp.CppBinary;
import org.gradle.language.cpp.CppComponent;
import org.gradle.language.cpp.CppExecutable;
import org.gradle.language.cpp.CppLibrary;
import org.gradle.language.cpp.CppSharedLibrary;
import org.gradle.language.cpp.CppStaticLibrary;
import org.gradle.language.cpp.tasks.CppCompile;
import org.gradle.nativeplatform.MachineArchitecture;
import org.gradle.nativeplatform.test.cpp.CppTestExecutable;
import org.gradle.nativeplatform.test.cpp.CppTestSuite;

import javax.inject.Inject;
import java.util.List;
import java.util.NoSuchElementException;
import java.util.Objects;
import java.util.concurrent.Callable;
import java.util.stream.Collectors;
import java.util.stream.StreamSupport;

import static java.util.stream.Collectors.joining;

public class CoreVisualStudioIdePlugin implements Plugin<Project> {
    private final ProjectLayout layout;
    private final ObjectFactory objects;
    private final ProviderFactory providers;
    private final ConfigurationContainer configurations;

    @Inject
    public CoreVisualStudioIdePlugin(ProjectLayout layout, ObjectFactory objects, ProviderFactory providers, ConfigurationContainer configurations) {
        this.layout = layout;
        this.objects = objects;
        this.providers = providers;
        this.configurations = configurations;
    }

    @Override
    public void apply(Project project) {
        project.getPluginManager().apply("dev.nokee.visual-studio-ide-base");

        VisualStudioIdeProjectExtension extension = project.getExtensions().getByType(VisualStudioIdeProjectExtension.class);
        project.getComponents().withType(CppComponent.class).configureEach(component -> {
            extension.getProjects().register(component.getName(), it -> configure((DefaultVisualStudioIdeProject) it, component));
            if (component instanceof CppApplication || component instanceof CppTestSuite) {
                project.getTasks().withType(Copy.class).configureEach(task -> {
                    String action = Objects.toString(project.property("dev.nokee.internal.visualStudio.bridge.Action"));
                    String platformName = Objects.toString(project.property("dev.nokee.internal.visualStudio.bridge.PlatformName"));
                    String configuration =  Objects.toString(project.property("dev.nokee.internal.visualStudio.bridge.Configuration"));
                    String projectName = Objects.toString(project.property("dev.nokee.internal.visualStudio.bridge.ProjectName"));
                    if (task.getName().equals("_visualStudio__" + action + "_" + projectName + "_" + configuration + "_" + platformName)) {
                        task.from((Callable<?>) () -> {
                            return component.getBinaries().get().stream().filter(it -> it instanceof CppTestExecutable || it.getName().contains(configuration.substring(1))).findFirst().orElseThrow(() -> new NoSuchElementException("No value present")).getRuntimeLibraries();
                        });
                    }
                });
            }
        });

    }

    private static FileCollection cppSourceOf(CppComponent component) {
        ExtensionAware extensible = (ExtensionAware) component;
        if (extensible.getExtensions().getExtraProperties().has("cppSource")) {
            return (FileCollection) extensible.getExtensions().getExtraProperties().get("cppSource");
        }

        return component.getCppSource();
    }

    private void configure(DefaultVisualStudioIdeProject ideProject, CppComponent component) {
        ideProject.getGeneratorTask().configure(task -> {
            task.getProjectLocation().set(layout.getProjectDirectory().file(component.getBaseName().map(it -> it + ".vcxproj")));
        });
        ideProject.getSourceFiles().from(cppSourceOf(component));
        ideProject.getHeaderFiles().from(component.getHeaderFiles());
        component.getBinaries().whenElementFinalized(new Action<CppBinary>() {
            public void execute(CppBinary binary) {
                if (component instanceof CppLibrary && ((CppLibrary) component).getLinkage().get().size() > 1 && binary instanceof CppStaticLibrary) {
                    return; // ignore non-shared library
                }

                DefaultVisualStudioIdeTarget target = new DefaultVisualStudioIdeTarget(projectConfiguration(binary), objects);
                Provider<CppBinary> developmentBinary = providers.provider(() -> binary);

                target.getProductLocation().set(toProductLocation(binary));
                target.getProperties().put("ConfigurationType", toConfigurationType(binary));
                target.getProperties().put("UseDebugLibraries", true);
                target.getProperties().put("PlatformToolset", "v142");
                target.getProperties().put("CharacterSet", "Unicode");
                target.getProperties().put("LinkIncremental", true);
                target.getItemProperties().maybeCreate("ClCompile")
                        .put("AdditionalIncludeDirectories", developmentBinary.flatMap(this::toAdditionalIncludeDirectories))
                        .put("LanguageStandard", developmentBinary.flatMap(toLanguageStandard()))
                        .put("PreprocessorDefinitions", developmentBinary.flatMap(this::toMacros))
                ;
                target.getItemProperties().maybeCreate("Link")
                        .put("SubSystem", developmentBinary.flatMap(toSubSystem()));

                ideProject.getTargets().add(target);
            }

            private Provider<String> toMacros(CppBinary binary) {
                return providers.provider(() -> binary.getCompileTask().get().getMacros().entrySet().stream().map(it -> it.getKey() + "=" + it.getValue()).collect(Collectors.joining(";")) + ";%(PreprocessorDefinitions)");
            }

            private Provider<RegularFile> toProductLocation(CppBinary binary) {
                if (binary instanceof CppExecutable) {
                    return ((CppExecutable) binary).getExecutableFile();
                } else if (binary instanceof CppSharedLibrary) {
                    return ((CppSharedLibrary) binary).getRuntimeFile();
                } else if (binary instanceof CppStaticLibrary) {
                    return ((CppStaticLibrary) binary).getLinkFile();
                } else if (binary instanceof CppTestExecutable) {
                    return ((CppTestExecutable) binary).getExecutableFile();
                }
                throw unsupportedBinaryType(binary);
            }

            private IllegalArgumentException unsupportedBinaryType(CppBinary binary) {
                return new IllegalArgumentException(String.format("Unsupported binary '%s'.", binary.getClass().getSimpleName()));
            }

            private String toConfigurationType(CppBinary binary) {
                if (binary instanceof CppSharedLibrary) {
                    return "DynamicLibrary";
                } else if (binary instanceof CppStaticLibrary) {
                    return "StaticLibrary";
                } else if (binary instanceof CppExecutable || binary instanceof CppTestExecutable) {
                    return "Application";
                }
                throw unsupportedBinaryType(binary);
            }

            private Provider<String> toAdditionalIncludeDirectories(CppBinary binary) {
                return binary.getCompileIncludePath().getElements().map(it -> {
                    return StreamSupport.stream(it.spliterator(), false).map(location -> "\"" + location.getAsFile().getAbsolutePath() + "\"").collect(joining(";"));
                });
            }

            private Transformer<Provider<String>, CppBinary> toSubSystem() {
                return new Transformer<Provider<String>, CppBinary>() {
                    @Override
                    public Provider<String> transform(CppBinary binary) {
                        if (binary instanceof CppExecutable) {
                            return ((CppExecutable) binary).getLinkTask().flatMap(it -> it.getLinkerArgs().flatMap(this::asVisualStudioIdeSubSystemValue));
                        }
                        return null;
                    }

                    private Provider<String> asVisualStudioIdeSubSystemValue(List<String> args) {
                        return providers.provider(ofVisualStudioIdeSubSystemValue(args));
                    }

                    private Callable<String> ofVisualStudioIdeSubSystemValue(List<String> args) {
                        return () -> args.stream()
                                .filter(this::forSubSystemLinkerFlag)
                                .findFirst() // TODO: We may want to use the last one, it depends how Visual Studio deal with flag duplicate
                                .map(this::withoutLinkFlagPrefix)
                                .orElse("Default");
                    }

                    private boolean forSubSystemLinkerFlag(String arg) {
                        return arg.matches("^[-/]SUBSYSTEM:.+");
                    }

                    private String withoutLinkFlagPrefix(String subSystemLinkerFlag) {
                        return capitalize(subSystemLinkerFlag.substring(11).toLowerCase());
                    }

                    private String capitalize(String s) {
                        return Character.toLowerCase(s.charAt(0)) + s.substring(1);
                    }
                };
            }

            private Transformer<Provider<String>, CppBinary> toLanguageStandard() {
                return new Transformer<Provider<String>, CppBinary>() {
                    @Override
                    public Provider<String> transform(CppBinary binary) {
                        return binary.getCompileTask().flatMap(this::compilerArgsToLanguageStandard);
                    }

                    private Provider<String> compilerArgsToLanguageStandard(CppCompile task) {
                        return task.getCompilerArgs().flatMap(this::asVisualStudioIdeLanguageStandardValue);
                    }

                    private Provider<String> asVisualStudioIdeLanguageStandardValue(List<String> args) {
                        return providers.provider(ofVisualStudioIdeLanguageStandardValue(args));
                    }

                    private Callable<String> ofVisualStudioIdeLanguageStandardValue(List<String> args) {
                        return () -> args.stream()
                                .filter(this::forStdCppFlag)
                                .findFirst()
                                .map(this::toIntellisenseLanguageStandardValue)
                                .orElse("Default");
                    }

                    private boolean forStdCppFlag(String arg) {
                        return arg.matches("^[-/]std:c++.+");
                    }

                    private String toIntellisenseLanguageStandardValue(String stdCppFlag) {
                        if (stdCppFlag.endsWith("c++14")) {
                            return "stdcpp14";
                        } else if (stdCppFlag.endsWith("c++17")) {
                            return "stdcpp17";
                        } else if (stdCppFlag.endsWith("c++latest")) {
                            return "stdcpplatest";
                        }
                        return "Default";
                    }
                };
            }
        });
    }

    private VisualStudioIdeProjectConfiguration projectConfiguration(CppBinary binary) {
        VisualStudioIdePlatform idePlatform = null;
        if (binary.getTargetMachine().getArchitecture().getName().equals(MachineArchitecture.X86_64)) {
            idePlatform = VisualStudioIdePlatforms.X64;
        } else if (binary.getTargetMachine().getArchitecture().getName().equals(MachineArchitecture.X86)) {
            idePlatform = VisualStudioIdePlatforms.WIN32;
        } else {
            throw new IllegalArgumentException("Unsupported architecture for Visual Studio IDE.");
        }

        VisualStudioIdeConfiguration configuration = null;
        if (binary.getName().contains("ebug")) {
            configuration = VisualStudioIdeConfiguration.of("Debug");
        } else if (binary.getName().contains("elease")) {
            configuration = VisualStudioIdeConfiguration.of("Release");
        } else if (binary instanceof CppTestExecutable) {
            if (Objects.requireNonNull(configurations.getByName("nativeLink" + capitalize(qualifyingName(binary))).getAttributes().getAttribute(CppBinary.OPTIMIZED_ATTRIBUTE))) {
                configuration = VisualStudioIdeConfiguration.of("Release");
            } else {
                configuration = VisualStudioIdeConfiguration.of("Debug");
            }
        } else {
            throw new IllegalArgumentException("Unsupported build type for Visual Studio IDE.");
        }

        return VisualStudioIdeProjectConfiguration.of(configuration, idePlatform);
    }

    //region Names
    private static String qualifyingName(CppBinary binary) {
        // The binary name follow the pattern <componentName><variantName>[Executable]
        String result = binary.getName();
        if (result.startsWith("main")) {
            result = result.substring("main".length());
        }

        // CppTestExecutable
        if (binary instanceof CppTestExecutable) {
            result = result.substring(0, binary.getName().length() - "Executable".length());
        }

        return uncapitalize(result);
    }

    private static String capitalize(String s) {
        return Character.toUpperCase(s.charAt(0)) + s.substring(1);
    }

    private static String uncapitalize(String s) {
        return Character.toLowerCase(s.charAt(0)) + s.substring(1);
    }
    //endregion
}
