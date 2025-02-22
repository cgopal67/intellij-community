// Copyright 2000-2023 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package com.intellij.codeInsight.daemon.impl.quickfix;

import com.intellij.codeInsight.daemon.QuickFixBundle;
import com.intellij.codeInsight.generation.PsiMethodMember;
import com.intellij.codeInsight.intention.impl.BaseIntentionAction;
import com.intellij.codeInsight.intention.preview.IntentionPreviewInfo;
import com.intellij.codeInsight.lookup.LookupElement;
import com.intellij.codeInsight.template.PsiElementResult;
import com.intellij.codeInsight.template.TemplateBuilderFactory;
import com.intellij.codeInsight.template.TemplateBuilderImpl;
import com.intellij.codeInsight.template.impl.ConstantNode;
import com.intellij.codeInspection.LocalQuickFixAndIntentionActionOnPsiElement;
import com.intellij.ide.util.MemberChooser;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.editor.Document;
import com.intellij.openapi.editor.Editor;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.util.Computable;
import com.intellij.psi.*;
import com.intellij.psi.codeStyle.CodeStyleManager;
import com.intellij.psi.util.PsiTreeUtil;
import com.intellij.util.ObjectUtils;
import com.intellij.util.containers.ContainerUtil;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.*;

public class InitializeFinalFieldInConstructorFix extends LocalQuickFixAndIntentionActionOnPsiElement {
  private static final Logger LOG = Logger.getInstance(InitializeFinalFieldInConstructorFix.class);

  public InitializeFinalFieldInConstructorFix(@NotNull PsiField field) {
    super(field);
  }

  @NotNull
  @Override
  public String getText() {
    return QuickFixBundle.message("initialize.final.field.in.constructor.name");
  }

  @NotNull
  @Override
  public String getFamilyName() {
    return getText();
  }

  @Override
  public boolean isAvailable(@NotNull Project project,
                             @NotNull PsiFile file,
                             @Nullable Editor editor,
                             @NotNull PsiElement startElement,
                             @NotNull PsiElement endElement) {
    PsiField field = ObjectUtils.tryCast(startElement, PsiField.class);
    if (field == null) return false;
    if (!field.isValid() || field.hasModifierProperty(PsiModifier.STATIC) || field.hasInitializer()) {
      return false;
    }

    final PsiClass containingClass = field.getContainingClass();
    if (containingClass == null || containingClass.getName() == null){
      return false;
    }

    final PsiManager manager = field.getManager();
    return manager != null && BaseIntentionAction.canModify(field);
  }

  @Override
  public void invoke(@NotNull Project project,
                     @NotNull PsiFile file,
                     @Nullable Editor editor,
                     @NotNull PsiElement startElement,
                     @NotNull PsiElement endElement) {
    PsiField field = ObjectUtils.tryCast(startElement, PsiField.class);
    if (field == null) return;
    final PsiClass myClass = field.getContainingClass();
    if (myClass == null) return;
    if (myClass.getConstructors().length == 0) {
      ApplicationManager.getApplication().runWriteAction(
        (Computable<PsiMethod>)() -> AddDefaultConstructorFix.addDefaultConstructor(myClass));
    }

    PsiMethod[] ctors = CreateConstructorParameterFromFieldFix.filterConstructorsIfFieldAlreadyAssigned(myClass.getConstructors(), field)
      .toArray(PsiMethod.EMPTY_ARRAY);
    final List<PsiMethod> constructors = choose(ctors, project);

    ApplicationManager.getApplication().runWriteAction(() -> addFieldInitialization(constructors, field, project, editor));
  }

  @Override
  public @NotNull IntentionPreviewInfo generatePreview(@NotNull Project project, @NotNull Editor editor, @NotNull PsiFile file) {
    PsiField field = PsiTreeUtil.findSameElementInCopy(ObjectUtils.tryCast(getStartElement(), PsiField.class), file);
    if (field == null) return IntentionPreviewInfo.EMPTY;
    final PsiClass myClass = field.getContainingClass();
    if (myClass == null) return IntentionPreviewInfo.EMPTY;
    if (myClass.getConstructors().length == 0) {
      AddDefaultConstructorFix.addDefaultConstructor(myClass);
    }

    PsiMethod[] ctors = CreateConstructorParameterFromFieldFix.filterConstructorsIfFieldAlreadyAssigned(myClass.getConstructors(), field)
      .toArray(PsiMethod.EMPTY_ARRAY);
    final List<PsiMethod> constructors = Arrays.asList(ctors);

    addFieldInitialization(constructors, field, project, editor);
    return IntentionPreviewInfo.DIFF;
  }

  private static void addFieldInitialization(@NotNull List<? extends PsiMethod> constructors,
                                             @NotNull PsiField field,
                                             @NotNull Project project,
                                             @Nullable Editor editor) {
    if (constructors.isEmpty()) return;

    final LookupElement[] suggestedInitializers = AddVariableInitializerFix.suggestInitializer(field);

    final List<SmartPsiElementPointer<PsiExpression>> rExprPointers = new ArrayList<>(constructors.size());
    for (PsiMethod constructor : constructors) {
      PsiExpression initializer = addFieldInitialization(constructor, suggestedInitializers, field, project);
      rExprPointers.add(SmartPointerManager.getInstance(project).createSmartPsiElementPointer(initializer));
    }
    Document doc = Objects.requireNonNull(field.getContainingFile().getViewProvider().getDocument());
    PsiDocumentManager.getInstance(project).doPostponedOperationsAndUnblockDocument(doc);
    List<PsiExpression> rExpressions = ContainerUtil.mapNotNull(rExprPointers, SmartPsiElementPointer::getElement);
    runAssignmentTemplate(rExpressions, suggestedInitializers, editor);
  }
  
  private static void runAssignmentTemplate(@NotNull final List<? extends PsiExpression> initializers,
                                    final LookupElement @NotNull [] suggestedInitializers,
                                    @Nullable Editor editor) {
    if (editor == null) return;
    LOG.assertTrue(!initializers.isEmpty());
    final PsiExpression initializer = Objects.requireNonNull(ContainerUtil.getFirstItem(initializers));
    PsiElement context = initializers.size() == 1 ? initializer : PsiTreeUtil.findCommonParent(initializers);
    if (context == null) return;
    final TemplateBuilderImpl builder = (TemplateBuilderImpl)TemplateBuilderFactory.getInstance().createTemplateBuilder(context);
    for (PsiExpression e : initializers) {
      builder.replaceElement(e, new ConstantNode(new PsiElementResult(suggestedInitializers[0].getPsiElement())).withLookupItems(suggestedInitializers));
    }
    builder.run(editor, false);
  }

  @NotNull
  private static PsiExpression addFieldInitialization(@NotNull PsiMethod constructor,
                                                      LookupElement @NotNull [] suggestedInitializers,
                                                      @NotNull PsiField field,
                                                      @NotNull Project project) {
    PsiCodeBlock methodBody = constructor.getBody();
    if (methodBody == null) {
      //incomplete code
      CreateFromUsageUtils.setupMethodBody(constructor);
      methodBody = constructor.getBody();
      LOG.assertTrue(methodBody != null);
    }

    final String fieldName = field.getName();
    String stmtText = fieldName + " = " + suggestedInitializers[0].getPsiElement().getText() + ";";
    if (methodContainsParameterWithName(constructor, fieldName)) {
      stmtText = "this." + stmtText;
    }

    final PsiManager psiManager = PsiManager.getInstance(project);
    final PsiElementFactory factory = JavaPsiFacade.getElementFactory(psiManager.getProject());
    final CodeStyleManager codeStyleManager = CodeStyleManager.getInstance(project);

    final PsiExpressionStatement addedStatement = (PsiExpressionStatement)methodBody.add(codeStyleManager
      .reformat(factory.createStatementFromText(stmtText, methodBody)));
    return Objects.requireNonNull(((PsiAssignmentExpression)addedStatement.getExpression()).getRExpression());
  }

  private static boolean methodContainsParameterWithName(@NotNull PsiMethod constructor, @NotNull String name) {
    for (PsiParameter parameter : constructor.getParameterList().getParameters()) {
      if (name.equals(parameter.getName())) {
        return true;
      }
    }
    return false;
  }

  @NotNull
  private static List<PsiMethod> choose(PsiMethod @NotNull [] ctors, @NotNull final Project project) {
    if (ApplicationManager.getApplication().isUnitTestMode()) {
      return Arrays.asList(ctors);
    }

    if (ctors.length == 1) {
      return Collections.singletonList(ctors[0]);
    }

    if (ctors.length > 1) {
      final MemberChooser<PsiMethodMember> chooser = new MemberChooser<>(toPsiMethodMemberArray(ctors), false, true, project);
      chooser.setTitle(QuickFixBundle.message("initialize.final.field.in.constructor.choose.dialog.title"));
      chooser.show();

      final List<PsiMethodMember> chosenMembers = chooser.getSelectedElements();
      if (chosenMembers != null) {
        return Arrays.asList(toPsiMethodArray(chosenMembers));
      }
    }

    return Collections.emptyList();
  }

  private static PsiMethodMember @NotNull [] toPsiMethodMemberArray(PsiMethod @NotNull [] methods) {
    final PsiMethodMember[] result = new PsiMethodMember[methods.length];
    for (int i = 0; i < methods.length; i++) {
      result[i] = new PsiMethodMember(methods[i]);
    }
    return result;
  }

  private static PsiMethod @NotNull [] toPsiMethodArray(@NotNull List<? extends PsiMethodMember> methodMembers) {
    final PsiMethod[] result = new PsiMethod[methodMembers.size()];
    int i = 0;
    for (PsiMethodMember methodMember : methodMembers) {
      result[i++] = methodMember.getElement();
    }
    return result;
  }

  @Nullable
  @Override
  public PsiElement getElementToMakeWritable(@NotNull PsiFile currentFile) {
    return currentFile;
  }

  @Override
  public boolean startInWriteAction() {
    return false;
  }
}
