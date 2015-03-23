/*
 * Copyright 2012 Google Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.google.template.soy.exprparse;

import com.google.template.soy.base.SourceLocation;
import com.google.template.soy.base.SoySyntaxException;
import com.google.template.soy.exprtree.ExprRootNode;
import com.google.template.soy.soyparse.ErrorReporter.Checkpoint;
import com.google.template.soy.soyparse.TransitionalThrowingErrorReporter;

import java.util.List;

/**
 * Utilities for parsing expressions.
 *
 * <p> Important: Do not use outside of Soy code (treat as superpackage-private).
 *
 * @deprecated Use {@link ExpressionParser} methods directly. TODO(user): remove this class.
 */
@Deprecated
public final class ExprParseUtils {

  private ExprParseUtils() {}

  /**
   * Attempts to parse the given exprText as a Soy expression list. If successful, returns the
   * expression list. If unsuccessful, throws a SoySyntaxException.
   *
   *
   * @param exprListText The text to parse.
   * @return The parsed expression list.
   * @deprecated Use {@link ExpressionParser#parseExpressionList()} directly.
   */
  @Deprecated
  public static List<ExprRootNode<?>> parseExprListElseThrowSoySyntaxException(
      String exprListText, SourceLocation sourceLocation) {
    TransitionalThrowingErrorReporter errorReporter = new TransitionalThrowingErrorReporter();
    List<ExprRootNode<?>> exprs
        = new ExpressionParser(exprListText, sourceLocation, errorReporter).parseExpressionList();
    errorReporter.throwIfErrorsPresent();
    return exprs;
  }

  /**
   * Attempts to parse the given exprText as a Soy expression. If successful, returns the
   * expression. If unsuccessful, throws a SoySyntaxException.
   *
   * @param exprText The text to parse.
   * @param errorMsg The error message for the SoySyntaxException when parsing is unsuccessful.
   * @return The parsed expression.
   * @deprecated Use {@link ExpressionParser#parseExpression()} directly.
   */
  @Deprecated
  public static ExprRootNode<?> parseExprElseThrowSoySyntaxException(
      String exprText, String errorMsg) {
    TransitionalThrowingErrorReporter errorReporter = new TransitionalThrowingErrorReporter();
    ExprRootNode<?> rootNode = new ExpressionParser(exprText, SourceLocation.UNKNOWN, errorReporter)
        .parseExpression();
    try {
      errorReporter.throwIfErrorsPresent();
    } catch (SoySyntaxException e) {
      // Re-throw the exception wrapped with the custom error message.
      throw SoySyntaxException.createCausedWithoutMetaInfo(errorMsg, e);
    }
    return rootNode;
  }

  /**
   * Attempts to parse the given exprText as a Soy expression. If successful, returns the
   * expression. If unsuccessful, returns null.
   *
   * <p> This function is used for situations where the expression might be in Soy V1 syntax (i.e.
   * older commands like 'print' and 'if').
   *
   * @param exprText The text to parse.
   * @return The parsed expression, or null if parsing was unsuccessful.
   * @deprecated Use {@link ExpressionParser#parseExpression()} directly.
   */
  @Deprecated
  public static ExprRootNode<?> parseExprElseNull(String exprText) {
    TransitionalThrowingErrorReporter errorReporter = new TransitionalThrowingErrorReporter();
    Checkpoint checkpoint = errorReporter.checkpoint();
    ExprRootNode<?> rootNode = new ExpressionParser(exprText, SourceLocation.UNKNOWN, errorReporter)
        .parseExpression();
    return errorReporter.errorsSince(checkpoint)
        ? null
        : rootNode;
  }
}
