// Copyright 2000-2023 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package com.intellij.codeInspection.actions

import com.intellij.codeInspection.CommonProblemDescriptor
import com.intellij.codeInspection.ProblemDescriptor
import com.intellij.codeInspection.QuickFix
import com.intellij.modcommand.ModCommand
import com.intellij.modcommand.ModCommandAction
import com.intellij.modcommand.ModCommandExecutor
import com.intellij.modcommand.ModCommandExecutor.BatchExecutionResult
import com.intellij.modcommand.ModCommandQuickFix
import com.intellij.openapi.project.Project

open class PerformFixesTask(project: Project, descriptors: List<CommonProblemDescriptor>, quickFixClass: Class<*>?) :
  AbstractPerformFixesTask(project, descriptors.toTypedArray(), quickFixClass) {

  override fun <D : CommonProblemDescriptor> collectFix(fix: QuickFix<D>, descriptor: D, project: Project): BatchExecutionResult {
    if (fix is ModCommandQuickFix) {
      descriptor as ProblemDescriptor
      val command: ModCommand = fix.perform(project, descriptor)
      return ModCommandExecutor.getInstance().executeInBatch(ModCommandAction.ActionContext.from(descriptor), command)
    }
    fix.applyFix(project, descriptor)
    return ModCommandExecutor.Result.SUCCESS
  }
}