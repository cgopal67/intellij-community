// Copyright 2000-2023 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package org.jetbrains.kotlin.idea.k2.refactoring.safeDelete

import com.intellij.psi.JavaPsiFacade
import com.intellij.psi.PsiClass
import com.intellij.psi.PsiElement
import com.intellij.psi.search.GlobalSearchScope
import com.intellij.psi.util.parentOfType
import org.jetbrains.kotlin.analysis.api.KtAllowAnalysisOnEdt
import org.jetbrains.kotlin.analysis.api.lifetime.allowAnalysisOnEdt
import org.jetbrains.kotlin.asJava.unwrapped
import org.jetbrains.kotlin.idea.references.mainReference
import org.jetbrains.kotlin.idea.test.InTextDirectivesUtils
import org.jetbrains.kotlin.idea.test.KotlinLightCodeInsightFixtureTestCase
import org.jetbrains.kotlin.idea.test.ProjectDescriptorWithStdlibSources
import org.jetbrains.kotlin.idea.util.application.executeWriteCommand
import org.jetbrains.kotlin.psi.KtSimpleNameExpression

abstract class AbstractK2BindToElementTest : KotlinLightCodeInsightFixtureTestCase() {
    override fun isFirPlugin() = true

    override fun getProjectDescriptor() = ProjectDescriptorWithStdlibSources.getInstanceWithStdlibSources()

    @OptIn(KtAllowAnalysisOnEdt::class)
    protected fun doTest(path: String) {
        myFixture.copyDirectoryToProject("", "")
        val file = myFixture.configureByFile(path)
        val elem = file.findElementAt(myFixture.caretOffset) ?: error("Couldn't find element at caret")
        val nameReference = elem.parentOfType<KtSimpleNameExpression>(withSelf = true)
            ?: error("Element at caret isn't of type 'KtSimpleNameExpression'")
        val bindTarget = findElementToBind()?.unwrapped ?: error("Could not find element to bind")
        myFixture.project.executeWriteCommand("bindToElement") {
            allowAnalysisOnEdt {
                nameReference.mainReference.bindToElement(bindTarget)
            }
        }
        myFixture.checkResultByFile("${myFixture.file.name}.after")
    }

    private fun findElementToBind(): PsiElement? {
        val classToBind = InTextDirectivesUtils.findStringWithPrefixes(file.text, BIND_TO)
        return classToBind?.let(::findClass)
    }

    private fun findClass(fqn: String): PsiClass? = JavaPsiFacade.getInstance(myFixture.project)
      .findClass(fqn, GlobalSearchScope.allScope(myFixture.project))

    private companion object {
        const val BIND_TO = "BIND_TO"
    }
}