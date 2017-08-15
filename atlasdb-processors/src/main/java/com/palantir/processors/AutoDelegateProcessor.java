/*
 * Copyright 2017 Palantir Technologies, Inc. All rights reserved.
 *
 * Licensed under the BSD-3 License (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://opensource.org/licenses/BSD-3-Clause
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.palantir.processors;

import java.io.IOException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.stream.Collectors;

import javax.annotation.processing.AbstractProcessor;
import javax.annotation.processing.Filer;
import javax.annotation.processing.FilerException;
import javax.annotation.processing.Messager;
import javax.annotation.processing.ProcessingEnvironment;
import javax.annotation.processing.Processor;
import javax.annotation.processing.RoundEnvironment;
import javax.lang.model.SourceVersion;
import javax.lang.model.element.Element;
import javax.lang.model.element.ElementKind;
import javax.lang.model.element.ExecutableElement;
import javax.lang.model.element.Modifier;
import javax.lang.model.element.PackageElement;
import javax.lang.model.element.TypeElement;
import javax.lang.model.type.DeclaredType;
import javax.lang.model.type.MirroredTypeException;
import javax.lang.model.type.TypeKind;
import javax.lang.model.type.TypeMirror;
import javax.lang.model.util.Elements;
import javax.lang.model.util.Types;
import javax.tools.Diagnostic;

import com.google.auto.service.AutoService;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.MapMaker;
import com.google.common.collect.Sets;
import com.squareup.javapoet.JavaFile;
import com.squareup.javapoet.MethodSpec;
import com.squareup.javapoet.TypeName;
import com.squareup.javapoet.TypeSpec;

@AutoService(Processor.class)
public final class AutoDelegateProcessor extends AbstractProcessor {
    // We keep track of if this processor has been registered in a processing environment, to avoid registering it
    // twice. Therefore, we keep weak references to both the keys and the values, to avoid keeping such references in
    // memory unnecessarily.
    private static final ConcurrentMap<ProcessingEnvironment, Processor> registeredProcessors =
            new MapMaker().weakKeys().weakValues().concurrencyLevel(1).initialCapacity(1).makeMap();
    private static final String PREFIX = "AutoDelegate_";
    private static final String DELEGATE_METHOD = "delegate";

    private Types typeUtils;
    private Elements elementUtils;
    private Filer filer;
    private Messager messager;
    private AtomicBoolean abortProcessing = new AtomicBoolean(false);

    @Override
    public synchronized void init(ProcessingEnvironment processingEnv) {
        super.init(processingEnv);

        typeUtils = processingEnv.getTypeUtils();
        elementUtils = processingEnv.getElementUtils();
        filer = processingEnv.getFiler();
        messager = processingEnv.getMessager();

        if (registeredProcessors.putIfAbsent(processingEnv, this) != null) {
            messager.printMessage(
                    Diagnostic.Kind.NOTE, "AutoDelegate processor registered twice; disabling duplicate instance");
            abortProcessing.set(Boolean.TRUE);
        }
    }

    @Override
    public Set<String> getSupportedAnnotationTypes() {
        return ImmutableSet.of(AutoDelegate.class.getCanonicalName());
    }

    @Override
    public SourceVersion getSupportedSourceVersion() {
        return SourceVersion.RELEASE_8;
    }

    @Override
    public boolean process(Set<? extends TypeElement> annotations, RoundEnvironment roundEnv) {
        if (abortProcessing.get() == Boolean.TRUE) {
            // Another instance of AutoDelegateProcessor is running in the current processing environment.
            return false;
        }

        Set<String> generatedInterfaces = new HashSet<>();
        for (Element annotatedElement : roundEnv.getElementsAnnotatedWith(AutoDelegate.class)) {
            try {
                validateAnnotatedElement(annotatedElement);
                TypeElement typeElement = (TypeElement) annotatedElement;

                AutoDelegate annotation = annotatedElement.getAnnotation(AutoDelegate.class);
                InterfaceToExtend interfaceToExtend = validateAnnotationAndCreateTypeToExtend(annotation, typeElement);

                if (generatedInterfaces.contains(interfaceToExtend.getCanonicalName())) {
                    continue;
                }
                generatedInterfaces.add(interfaceToExtend.getCanonicalName());

                generateCode(interfaceToExtend);
            } catch (FilerException e) {
                // Happens when same file is written twice.
                warn(annotatedElement, e.getMessage());
            } catch (ProcessingException e) {
                error(e.getElement(), e.getMessage());
            } catch (IOException | RuntimeException e) {
                error(annotatedElement, e.getMessage());
            }
        }

        return false;
    }

    private void validateAnnotatedElement(Element annotatedElement) throws ProcessingException {
        ElementKind kind = annotatedElement.getKind();
        if (kind != ElementKind.INTERFACE && kind != ElementKind.CLASS) {
            throw new ProcessingException(annotatedElement, "Only classes or interfaces can be annotated with @%s",
                    AutoDelegate.class.getSimpleName());
        }
    }

    private InterfaceToExtend validateAnnotationAndCreateTypeToExtend(AutoDelegate annotation,
            TypeElement annotatedElement)
            throws ProcessingException {

        if (annotation == null) {
            throw new ProcessingException(annotatedElement, "Type %s doesn't have annotation @%s",
                    annotatedElement, AutoDelegate.class.getSimpleName());
        }

        TypeElement baseInterface = extractInterfaceFromAnnotation(annotation);
        PackageElement interfacePackage = elementUtils.getPackageOf(baseInterface);

        if (interfacePackage.isUnnamed()) {
            throw new ProcessingException(baseInterface, "Interface %s doesn't have a package", baseInterface);
        }

        List<TypeElement> superInterfaces = fetchSuperInterfaces(baseInterface);
        return new InterfaceToExtend(interfacePackage, baseInterface, superInterfaces.toArray(new TypeElement[0]));
    }

    private TypeElement extractInterfaceFromAnnotation(AutoDelegate annotation) {
        TypeElement interfaceType;
        try {
            // Throws a MirroredTypeException if the interface is not compiled.
            Class interfaceClass = annotation.interfaceToExtend();
            interfaceType = elementUtils.getTypeElement(interfaceClass.getCanonicalName());
        } catch (MirroredTypeException mte) {
            DeclaredType typeMirror = (DeclaredType) mte.getTypeMirror();
            interfaceType = (TypeElement) typeMirror.asElement();
        }

        return interfaceType;
    }

    private List<TypeElement> fetchSuperInterfaces(TypeElement baseInterface) {
        List<TypeMirror> interfacesQueue = new ArrayList<>(baseInterface.getInterfaces());
        Set<TypeMirror> interfacesSet = Sets.newHashSet(interfacesQueue);
        List<TypeElement> superInterfaceElements = new ArrayList<>();

        for (int i = 0; i < interfacesQueue.size(); i++) {
            TypeMirror superInterfaceMirror = interfacesQueue.get(i);
            TypeElement superInterfaceType = extractSuperInterface(superInterfaceMirror);
            superInterfaceElements.add(superInterfaceType);

            List<TypeMirror> newInterfaces = superInterfaceType.getInterfaces()
                    .stream()
                    .filter((newInteface) -> !interfacesSet.contains(newInteface))
                    .collect(Collectors.toList());
            interfacesSet.addAll(newInterfaces);
            interfacesQueue.addAll(newInterfaces);
        }

        return superInterfaceElements;
    }

    private TypeElement extractSuperInterface(TypeMirror superInterfaceMirror) {
        TypeElement superInterface;
        try {
            // Throws a MirroredTypeException if the interface is not compiled.
            superInterface = (TypeElement) typeUtils.asElement(superInterfaceMirror);
        } catch (MirroredTypeException mte) {
            DeclaredType typeMirror = (DeclaredType) mte.getTypeMirror();
            superInterface = (TypeElement) typeMirror.asElement();
        }
        return superInterface;
    }

    private void generateCode(InterfaceToExtend interfaceToExtend) throws IOException {
        TypeSpec.Builder interfaceBuilder = TypeSpec.interfaceBuilder(PREFIX + interfaceToExtend.getSimpleName());

        // Add modifiers
        TypeMirror interfaceType = interfaceToExtend.getType();
        if (interfaceToExtend.isPublic()) {
            interfaceBuilder.addModifiers(Modifier.PUBLIC);
        }
        interfaceBuilder.addSuperinterface(TypeName.get(interfaceType));

        // Add delegate method
        MethodSpec.Builder delegateMethod = MethodSpec
                .methodBuilder(DELEGATE_METHOD)
                .addModifiers(Modifier.PUBLIC, Modifier.ABSTRACT)
                .returns(TypeName.get(interfaceType));
        interfaceBuilder.addMethod(delegateMethod.build());

        // Add interface methods
        for (ExecutableElement methodElement : interfaceToExtend.getMethods()) {
            String returnStatement = (methodElement.getReturnType().getKind() == TypeKind.VOID) ? "" : "return ";

            MethodSpec.Builder interfaceMethod = MethodSpec
                    .overriding(methodElement)
                    .addModifiers(Modifier.DEFAULT)
                    .addStatement("$L$L().$L($L)",
                            returnStatement,
                            DELEGATE_METHOD,
                            methodElement.getSimpleName(),
                            methodElement.getParameters());

            interfaceBuilder.addMethod(interfaceMethod.build());
        }

        JavaFile
                .builder(interfaceToExtend.getPackageName(), interfaceBuilder.build())
                .build()
                .writeTo(filer);
    }

    /**
     * Prints a warn message.
     *
     * @param element The element which has caused the error. Can be null
     * @param msg The error message
     */
    private void warn(Element element, String msg) {
        messager.printMessage(Diagnostic.Kind.WARNING, msg, element);
    }

    /**
     * Prints an error message.
     *
     * @param element The element which has caused the error. Can be null
     * @param msg The error message
     */
    private void error(Element element, String msg) {
        messager.printMessage(Diagnostic.Kind.ERROR, msg, element);
    }
}
