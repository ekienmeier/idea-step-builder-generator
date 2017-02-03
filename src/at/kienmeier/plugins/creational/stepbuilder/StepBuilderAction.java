package at.kienmeier.plugins.creational.stepbuilder;

import com.intellij.codeInsight.hint.HintManager;
import com.intellij.openapi.actionSystem.AnAction;
import com.intellij.openapi.actionSystem.AnActionEvent;
import com.intellij.openapi.actionSystem.DataKeys;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.command.CommandProcessor;
import com.intellij.openapi.command.UndoConfirmationPolicy;
import com.intellij.openapi.editor.Editor;
import com.intellij.openapi.project.Project;
import com.intellij.psi.*;
import com.intellij.psi.codeStyle.CodeStyleManager;
import com.intellij.psi.search.GlobalSearchScope;
import com.intellij.psi.util.PsiTreeUtil;
import org.jetbrains.annotations.NotNull;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Created by Emanuel on 12.12.2015.
 * (C) 2015 Emanuel Kienmeier (ekienmeier@gmail.com)
 */
public class StepBuilderAction extends AnAction {
    public static final String PLUGIN_NAME = "Step Builder Generator Plugin";
    private PsiElementFactory psiElementFactory;
    private PsiAnnotation generatedAnnotation;
    private int generatedAnnotationUsages;
    private Project project;

    public void actionPerformed(AnActionEvent e) {
        project = DataKeys.PROJECT.getData(e.getDataContext());
        if (project == null) {
            return;
        }
        PsiFile psiFile = DataKeys.PSI_FILE.getData(e.getDataContext());
        Editor editor = DataKeys.EDITOR.getData(e.getDataContext());

        psiElementFactory = JavaPsiFacade.getInstance(project).getElementFactory();
        generatedAnnotation = psiElementFactory.createAnnotationFromText(String.format("@Generated(value = \"%s\")", PLUGIN_NAME), null);
        generatedAnnotationUsages = 0;

        PsiClass psiClass = getClassAtCursor(project, editor, psiFile);
        if (null != psiClass) {
            createStepBuilder(project, psiFile, psiClass, editor);
        }
    }

    private PsiClass getClassAtCursor(Project project, Editor editor, PsiFile file) {
        PsiDocumentManager.getInstance(project).commitAllDocuments();
        int offset = editor.getCaretModel().getOffset();
        PsiElement element = file.findElementAt(offset);

        do {
            element = PsiTreeUtil.getParentOfType(element, PsiClass.class);
        } while (element instanceof PsiTypeParameter);

        PsiClass psiClass = (PsiClass) element;

        if (psiClass instanceof PsiSyntheticClass) {
            return null;
        } else if (psiClass != null && !psiClass.isInterface()) {
            return psiClass;
        } else {
            return null;
        }
    }

    private PsiClass findClass(String fqClassName) {
        return JavaPsiFacade.getInstance(project).findClass(fqClassName, GlobalSearchScope.allScope(project));
    }

    private void createStepBuilder(final Project project, final PsiFile psiFile, final PsiClass psiClass, final Editor editor) {
        // Assumption: all final fields of the class are mandatory, all non-final fields are optional
        // TODO: change this assumption to something configurable, like a dialog
        ApplicationManager.getApplication().runWriteAction(new Runnable() {
            @Override
            public void run() {
                final CommandProcessor commandProcessor = CommandProcessor.getInstance();
                commandProcessor.executeCommand(psiClass.getProject(), new Runnable() {
                    @Override
                    public void run() {
                        commandProcessor.addAffectedFiles(project, psiFile.getVirtualFile());

                        PsiType psiClassType = createType(psiClass);

                        PsiField[] mandatoryFields = findMandatoryFields(psiClass);
                        PsiField[] optionalFields = findOptionalFields(psiClass);

                        if (mandatoryFields.length + optionalFields.length == 0) {
                            // nothing to generate - the class seems to be empty
                            HintManager.getInstance().showErrorHint(editor, "No fields have been found to generate a Step Builder for");
                            return;
                        }
                        addOrModifyConstructor(psiClass, mandatoryFields);
                        addGetters(psiClass, mandatoryFields);
                        addGetters(psiClass, optionalFields);

                        // generate the names of the inner interface classes
                        // interface name == "MandatoryFieldName" + "Step"
                        String[] interfaceNames = createInterfaceNames(mandatoryFields);

                        PsiClass finalInterfaceClass = createFinalInterfaceClass();
                        PsiType finalInterfaceClassType = createType(finalInterfaceClass);

                        PsiClass[] innerInterfaceClasses = createInnerInterfaceClasses(interfaceNames, finalInterfaceClass);
                        PsiType[] innerInterfaceClassTypes = createInnerInterfaceClassTypes(finalInterfaceClassType, innerInterfaceClasses);

                        // create the inner Builder class first, but don't add it to the class yet
                        PsiClass builderClass = createBuilderClass(innerInterfaceClasses);

                        // let's start with a static method as entry to the builder
                        addNewInstanceMethod(psiClass, innerInterfaceClassTypes[0]);

                        addBuildInterfaceMethod(finalInterfaceClass, psiClassType);

                        addOptionalFieldMethodsToFinalInterface(optionalFields, finalInterfaceClass, finalInterfaceClassType);

                        addInnerInterfaceClasses(psiClass, mandatoryFields, innerInterfaceClasses, innerInterfaceClassTypes);

                        // add all mandatory and optional fields to the builder als private fields
                        addFieldsToBuilderClass(builderClass, mandatoryFields);
                        addFieldsToBuilderClass(builderClass, optionalFields);

                        // create all methods for the mandatory fields of the builder
                        addBuilderMandatoryFieldMethods(builderClass, mandatoryFields, innerInterfaceClasses, innerInterfaceClassTypes);

                        // create all methods for the optional fields of the builder
                        addBuilderOptionalFieldMethods(builderClass, optionalFields, finalInterfaceClassType);

                        // the FinalStep's interface build() method is a bit more complex...
                        addBuilderBuildMethod(psiClass, builderClass, mandatoryFields, optionalFields, psiClassType);

                        addInnerClass(psiClass, builderClass);

                        // add the import for @Generated if it is used in the code
                        addImport(psiFile, "javax.annotation.Generated");
                        CodeStyleManager.getInstance(project).reformat(psiClass);
                    }
                }, "Generate Step Builder", null, UndoConfirmationPolicy.REQUEST_CONFIRMATION);
            }
        });
    }

    private void addImport(PsiFile psiFile, String fqClassName) {
        if (generatedAnnotationUsages > 0) {
            PsiClass generatedAnnotationClass = findClass(fqClassName);
            if (psiFile instanceof PsiImportHolder) {
                PsiImportHolder importHolder = (PsiImportHolder) psiFile;
                importHolder.importClass(generatedAnnotationClass);
            }
        }
    }

    private void addInnerClass(PsiClass theClass, PsiClass innerClass) {
        //if (null != theClass.findInnerClassByName(innerClass.getName(), false)) {
        theClass.add(innerClass);
        //}
    }

    private void addGetters(PsiClass psiClass, PsiField[] fields) {
        for (PsiField field : fields) {
            String getMethodName = "get" + firstCharToUpperCase(field.getName());
            //String getMethodAsText = String.format("public %s %s() { return %s; }", field.getType().getCanonicalText(), getMethodName, field.getName());
            // TODO: use regular createMethod() with proper PSI parameter list, otherwise isEquivalentTo() won't work, I guess
            // PsiMethod getMethod = psiElementFactory.createMethodFromText(getMethodAsText, null);
            PsiMethod getMethod = psiElementFactory.createMethod(getMethodName, field.getType());
            getMethod.getBody().add(psiElementFactory.createStatementFromText("return " + field.getName() + ";", null));
            if (psiClass.findMethodBySignature(getMethod, false) == null) {
                psiClass.add(getMethod);
            }
        }
    }

    @NotNull
    private PsiType createType(PsiClass psiClass) {
        return psiElementFactory.createType(psiClass);
    }

    private void addOptionalFieldMethodsToFinalInterface(PsiField[] fields, PsiClass finalInterfaceClass, PsiType finalInterfaceClassType) {
        for (PsiField field : fields) {
            PsiMethod optionalFieldMethod = psiElementFactory.createMethodFromText(String.format("public %s %s(%s %s);", finalInterfaceClassType.getCanonicalText(), field.getName(), field.getType().getCanonicalText(), field.getName()), null);
            finalInterfaceClass.add(optionalFieldMethod);
        }
    }

    @NotNull
    private PsiClass createFinalInterfaceClass() {
        PsiClass finalInterfaceClass = psiElementFactory.createInterface("FinalStep");
        addGeneratedAnnotation(finalInterfaceClass);
        finalInterfaceClass.getModifierList().setModifierProperty("public", true);
        return finalInterfaceClass;
    }

    @NotNull
    private PsiType[] createInnerInterfaceClassTypes(PsiType finalInterfaceClassType, PsiClass[] innerInterfaceClasses) {
        List<PsiType> innerInterfaceClassTypeList = new ArrayList<PsiType>();
        for (PsiClass innerInterfaceClass : innerInterfaceClasses) {
            PsiType interfaceClassType = createType(innerInterfaceClass);
            innerInterfaceClassTypeList.add(interfaceClassType);
        }
        innerInterfaceClassTypeList.add(finalInterfaceClassType);
        return innerInterfaceClassTypeList.toArray(new PsiType[innerInterfaceClassTypeList.size()]);
    }

    @NotNull
    private PsiClass[] createInnerInterfaceClasses(String[] interfaceNames, PsiClass finalInterfaceClass) {
        List<PsiClass> innerInterfaceClassList = new ArrayList<PsiClass>();
        for (String interfaceName : interfaceNames) {
            PsiClass interfaceClass = psiElementFactory.createInterface(interfaceName);
            addGeneratedAnnotation(interfaceClass);
            interfaceClass.getModifierList().setModifierProperty("public", true);
            innerInterfaceClassList.add(interfaceClass);
        }
        innerInterfaceClassList.add(finalInterfaceClass);
        return innerInterfaceClassList.toArray(new PsiClass[innerInterfaceClassList.size()]);
    }

    private void addOrModifyConstructor(PsiClass psiClass, PsiField[] fields) {
        List<PsiMethod> allConstructors = new ArrayList<PsiMethod>(Arrays.asList(psiClass.getConstructors()));
        // find a constructor whose parameter list contains all mandatory fields
        PsiMethod constructor = findConstructorWithParameters(psiClass, fields);
        if (null == constructor) {
            // there is no matching constructor, create a new one
            constructor = createPrivateConstructor(psiClass, fields);
            psiClass.add(constructor);
        } else {
            // found one, make it private if it isn't already
            if (!constructor.getModifierList().hasModifierProperty("private")) {
                constructor.getModifierList().setModifierProperty("private", true);
            }
            allConstructors.remove(constructor);
        }
        // remove all other constructors
        for (PsiMethod c : allConstructors) {
            c.delete();
        }
    }

    private void addBuildInterfaceMethod(PsiClass finalInterfaceClass, PsiType returnType) {
        PsiMethod buildInterfaceMethod = psiElementFactory.createMethodFromText(String.format("public %s build();", returnType.getCanonicalText()), null);
        finalInterfaceClass.add(buildInterfaceMethod);
    }

    private void addNewInstanceMethod(PsiClass psiClass, PsiType returnType) {
        PsiMethod newInstanceMethod = psiElementFactory.createMethod("newInstance", returnType);
        //addGeneratedAnnotation(newInstanceMethod);
        newInstanceMethod.getModifierList().setModifierProperty("public", true);
        newInstanceMethod.getModifierList().setModifierProperty("static", true);
        newInstanceMethod.getBody().add(psiElementFactory.createStatementFromText("return new Builder();", null));
        if (null == psiClass.findMethodBySignature(newInstanceMethod, false)) {
            psiClass.add(newInstanceMethod);
        }
    }

    private void addBuilderBuildMethod(PsiClass psiClass, PsiClass builderClass, PsiField[] mandatoryFields, PsiField[] optionalFields, PsiType returnType) {
        PsiMethod buildMethod = psiElementFactory.createMethod("build", returnType);
        buildMethod.getModifierList().setModifierProperty("public", true);
        // first, call the constructor of the class with the mandatory fields' parameters
        String buildMethodConstructorArgs = "";
        for (PsiField field : mandatoryFields) {
            if (!buildMethodConstructorArgs.isEmpty()) {
                buildMethodConstructorArgs += ", ";
            }
            buildMethodConstructorArgs += field.getName();
        }
        String buildMethodBody = String.format("%s theObject = new %s(%s);", psiClass.getName(), psiClass.getName(), buildMethodConstructorArgs);
        buildMethod.getBody().add(psiElementFactory.createStatementFromText(buildMethodBody, null));
        for (PsiField field : optionalFields) {
            buildMethod.getBody().add(psiElementFactory.createStatementFromText(String.format("theObject.%s = %s;", field.getName(), field.getName()), null));
        }
        buildMethod.getBody().add(psiElementFactory.createStatementFromText("return theObject;", null));
        builderClass.add(buildMethod);
    }

    private void addBuilderOptionalFieldMethods(PsiClass builderClass, PsiField[] fields, PsiType finalInterfaceClassType) {
        for (PsiField field : fields) {
            PsiMethod optionalFieldMethod = psiElementFactory.createMethodFromText(String.format("public %s %s(%s %s) { this.%s = %s; return this; }", finalInterfaceClassType.getCanonicalText(), field.getName(), field.getType().getCanonicalText(), field.getName(), field.getName(), field.getName()), null);
            builderClass.add(optionalFieldMethod);
        }
    }

    private void addBuilderMandatoryFieldMethods(PsiClass builderClass, PsiField[] fields, PsiClass[] innerInterfaceClasses, PsiType[] innerInterfaceClassTypes) {
        for (int i = 0; i < innerInterfaceClasses.length - 1; i++) {
            PsiMethod stepMethod = psiElementFactory.createMethodFromText(String.format("public %s %s() { this.%s = %s; return this; }", innerInterfaceClassTypes[i + 1].getCanonicalText(), fields[i].getName(), fields[i].getName(), fields[i].getName()), null);
            stepMethod.getParameterList().add(psiElementFactory.createParameter(fields[i].getName(), fields[i].getType()));
            builderClass.add(stepMethod);
        }
    }

    private void addInnerInterfaceClasses(PsiClass psiClass, PsiField[] fields, PsiClass[] innerInterfaceClasses, PsiType[] innerInterfaceClassTypes) {
        for (int i = 0; i < innerInterfaceClasses.length; i++) {
            PsiClass interfaceClass = innerInterfaceClasses[i];
            if (i < innerInterfaceClasses.length - 1) {
                PsiMethod stepInterfaceMethod = psiElementFactory.createMethodFromText(String.format("public %s %s();", innerInterfaceClassTypes[i + 1].getCanonicalText(), fields[i].getName()), null);
                stepInterfaceMethod.getParameterList().add(psiElementFactory.createParameter(fields[i].getName(), fields[i].getType()));
                interfaceClass.add(stepInterfaceMethod);

                PsiMethod stepMethod = psiElementFactory.createMethodFromText(String.format("public %s %s() { this.%s = %s; return this; }", innerInterfaceClassTypes[i + 1].getCanonicalText(), fields[i].getName(), fields[i].getName(), fields[i].getName()), null);
                addGeneratedAnnotation(stepMethod);
                stepMethod.getParameterList().add(psiElementFactory.createParameter(fields[i].getName(), fields[i].getType()));
            }
            psiClass.add(interfaceClass);
        }
    }

    @NotNull
    private String[] createInterfaceNames(PsiField[] fields) {
        List<String> interfaceNameList = new ArrayList<String>();
        for (PsiField mandatoryField : fields) {
            String interfaceName = firstCharToUpperCase(mandatoryField.getName()) + "Step";
            interfaceNameList.add(interfaceName);
        }
        return interfaceNameList.toArray(new String[interfaceNameList.size()]);
    }

    @NotNull
    private PsiClass createBuilderClass(PsiClass[] innerInterfaceClasses) {
        PsiClass builderClass = psiElementFactory.createClass("Builder");
        addGeneratedAnnotation(builderClass);
        builderClass.getModifierList().setModifierProperty("private", true);
        builderClass.getModifierList().setModifierProperty("static", true);
        builderClass.getModifierList().setModifierProperty("final", true);
        for (PsiClass interfaceClass : innerInterfaceClasses) {
            PsiJavaCodeReferenceElement referenceElement = psiElementFactory.createClassReferenceElement(interfaceClass);
            builderClass.getImplementsList().add(referenceElement);
        }
        return builderClass;
    }

    private void addFieldsToBuilderClass(PsiClass builderClass, PsiField[] fields) {
        for (PsiField field : fields) {
            PsiField builderField = psiElementFactory.createField(field.getName(), field.getType());
            builderField.getModifierList().setModifierProperty("private", true);
            builderClass.add(builderField);
        }
    }

    private String firstCharToUpperCase(String value) {
        Pattern pattern = Pattern.compile("(.)(.*)");
        Matcher matcher = pattern.matcher(value);
        if (matcher.matches() && matcher.groupCount() == 2) {
            String first = matcher.group(1);
            String rest = matcher.group(2);
            return String.format("%s%s", first.toUpperCase(), rest);
        }
        return value;
    }

    @NotNull
    private PsiMethod createPrivateConstructor(PsiClass psiClass, PsiField[] mandatoryFields) {
        PsiMethod newConstructor = psiElementFactory.createConstructor(psiClass.getName());
        //addGeneratedAnnotation(newConstructor);
        newConstructor.getModifierList().setModifierProperty("private", true);
        PsiParameterList parameterList = newConstructor.getParameterList();
        PsiCodeBlock codeBlock = newConstructor.getBody();
        for (PsiField mandatoryField : mandatoryFields) {
            String name = mandatoryField.getName();
            PsiType type = mandatoryField.getType();
            parameterList.add(psiElementFactory.createParameter(name, type));
            PsiStatement statement = psiElementFactory.createStatementFromText(String.format("this.%s = %s;%n", name, name), null);
            codeBlock.add(statement);
        }
        return newConstructor;
    }

    private void addGeneratedAnnotation(PsiModifierListOwner modifierListOwner) {
        modifierListOwner.getModifierList().addAfter(generatedAnnotation, null);
        generatedAnnotationUsages++;
    }

    private PsiMethod findConstructorWithParameters(PsiClass psiClass, PsiField[] fields) {
        PsiMethod[] constructors = psiClass.getConstructors();
        for (PsiMethod constructor : constructors) {
            PsiParameterList parameterList = constructor.getParameterList();
            if (fields.length == parameterList.getParametersCount()) {
                PsiParameter[] parameters = parameterList.getParameters();
                boolean parametersEqualFields = true;
                int i = 0;
                while (i < parameters.length && parametersEqualFields) {
                    String parameterType = parameters[i].getType().getCanonicalText();
                    String fieldType = fields[i].getType().getCanonicalText();
                    String parameterName = parameters[i].getName();
                    String fieldName = fields[i].getName();
                    parametersEqualFields = parameterType.equals(fieldType) && parameterName.equals(fieldName);
                    i++;
                }
                if (parametersEqualFields) {
                    // constructor already exists, return it
                    return constructor;
                }
            }
        }
        return null;
    }

    private PsiField[] findOptionalFields(PsiClass psiClass) {
        List<PsiField> optionalFields = new ArrayList<PsiField>();
        PsiField[] allFields = psiClass.getAllFields();
        for (PsiField psiField : allFields) {
            if (null != psiField.getModifierList() && !psiField.getModifierList().hasModifierProperty("final")) {
                optionalFields.add(psiField);
            }
        }
        return optionalFields.toArray(new PsiField[optionalFields.size()]);
    }

    private PsiField[] findMandatoryFields(PsiClass psiClass) {
        List<PsiField> mandatoryFields = new ArrayList<PsiField>();
        PsiField[] allFields = psiClass.getAllFields();
        for (PsiField psiField : allFields) {
            if (null != psiField.getModifierList() && psiField.getModifierList().hasModifierProperty("final")) {
                mandatoryFields.add(psiField);
            }
        }
        return mandatoryFields.toArray(new PsiField[mandatoryFields.size()]);
    }
}
