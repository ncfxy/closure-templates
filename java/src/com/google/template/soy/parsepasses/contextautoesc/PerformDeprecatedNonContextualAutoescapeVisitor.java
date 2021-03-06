/*
 * Copyright 2008 Google Inc.
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

package com.google.template.soy.parsepasses.contextautoesc;

import com.google.common.collect.ImmutableList;
import com.google.template.soy.base.internal.IdGenerator;
import com.google.template.soy.coredirectives.EscapeHtmlDirective;
import com.google.template.soy.exprtree.ExprNode;
import com.google.template.soy.shared.restricted.SoyPrintDirective;
import com.google.template.soy.soytree.AbstractSoyNodeVisitor;
import com.google.template.soy.soytree.AutoescapeMode;
import com.google.template.soy.soytree.PrintDirectiveNode;
import com.google.template.soy.soytree.PrintNode;
import com.google.template.soy.soytree.SoyNode;
import com.google.template.soy.soytree.SoyNode.ParentSoyNode;
import com.google.template.soy.soytree.TemplateNode;

/**
 * Visitor for performing deprecated-noncontextual autoescape.
 *
 * <p>Note: This does nothing for strict autoescaping, which is the new standard; this implements
 * the older non-contextual autoescaping that incorrectly treats everything as HTML. For backwards
 * compatibility, Soy continues to support that mode.
 *
 * <p>Important: Do not use outside of Soy code (treat as superpackage-private).
 *
 * <p>{@link #exec} should be called on a full parse tree. The directives on 'print' nodes may be
 * modified. There is no return value.
 *
 */
final class PerformDeprecatedNonContextualAutoescapeVisitor extends AbstractSoyNodeVisitor<Void> {

  /** The node id generator for the parse tree. Retrieved from the root SoyFileSetNode. */
  private final IdGenerator nodeIdGen;

  /** The autoescape mode of the current template. */
  private AutoescapeMode autoescapeMode;

  public PerformDeprecatedNonContextualAutoescapeVisitor(IdGenerator nodeIdGen) {
    this.nodeIdGen = nodeIdGen;
  }

  // -----------------------------------------------------------------------------------------------
  // Implementations for specific nodes.

  @Override
  protected void visitTemplateNode(TemplateNode node) {
    autoescapeMode = node.getAutoescapeMode();
    visitChildren(node);
    autoescapeMode = null;
  }

  @Override
  protected void visitPrintNode(PrintNode node) {

    if (autoescapeMode != AutoescapeMode.NONCONTEXTUAL) {
      // We're using one of the more modern escape modes; do a sanity check and return.  Make sure
      // that this pass is never used without first running the contextual autoescaper.
      if (node.getChildren().isEmpty()) {
        throw new IllegalStateException(
            String.format(
                "Internal error: A contextual or strict template has a print node that was never "
                    + "assigned any escape directives: %s at %s",
                node.toSourceString(), node.getSourceLocation()));
      }
      return;
    }

    // Traverse the list to (a) record whether we saw any directive that cancels autoescape
    // (including 'noAutoescape' of course) and (b) remove 'noAutoescape' directives.
    boolean shouldCancelAutoescape = false;
    for (PrintDirectiveNode directiveNode : ImmutableList.copyOf(node.getChildren())) {
      SoyPrintDirective directive = directiveNode.getPrintDirective();
      if (directive != null && directive.shouldCancelAutoescape()) {
        shouldCancelAutoescape = true;
        break;
      }
    }

    // If appropriate, apply autoescape by adding an |escapeHtml directive (should be applied first
    // because other directives may add HTML tags).
    // TODO(gboyer): This behavior is actually very confusing, because for escaping to be safe, it
    // needs to be applied last. The logic in Rewriter.java is superior because it inserts the
    // escaping before any SanitizedContentOperators but after other directives. In any case, the
    // motivation for fixing this is low because it would risk breaking old templates, which
    // ideally should migrate off of deprecated-noncontextual autoescape.
    if (autoescapeMode == AutoescapeMode.NONCONTEXTUAL && !shouldCancelAutoescape) {
      PrintDirectiveNode newEscapeHtmlDirectiveNode =
          new PrintDirectiveNode(
              nodeIdGen.genId(),
              node.getSourceLocation(),
              ImmutableList.<ExprNode>of(),
              new EscapeHtmlDirective(),
              /* isSynthetic= */ true);
      node.addChild(0, newEscapeHtmlDirectiveNode);
    }
  }

  // -----------------------------------------------------------------------------------------------
  // Fallback implementation.

  @Override
  protected void visitSoyNode(SoyNode node) {
    if (node instanceof ParentSoyNode<?>) {
      visitChildren((ParentSoyNode<?>) node);
    }
  }
}
