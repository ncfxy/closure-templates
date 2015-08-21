/*
 * Copyright 2015 Google Inc.
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

package com.google.template.soy.incrementaldomsrc;

import com.google.common.base.Preconditions;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.Maps;
import com.google.inject.Inject;
import com.google.template.soy.basetree.ParentNode;
import com.google.template.soy.data.SanitizedContent.ContentKind;
import com.google.template.soy.error.ErrorReporter;
import com.google.template.soy.error.SoyError;
import com.google.template.soy.exprtree.ExprNode;
import com.google.template.soy.exprtree.ExprRootNode;
import com.google.template.soy.exprtree.VarRefNode;
import com.google.template.soy.html.HtmlAttributeNode;
import com.google.template.soy.html.HtmlCloseTagNode;
import com.google.template.soy.html.HtmlOpenTagEndNode;
import com.google.template.soy.html.HtmlOpenTagNode;
import com.google.template.soy.html.HtmlOpenTagStartNode;
import com.google.template.soy.html.HtmlPrintNode;
import com.google.template.soy.html.HtmlTextNode;
import com.google.template.soy.html.HtmlVoidTagNode;
import com.google.template.soy.jssrc.SoyJsSrcOptions;
import com.google.template.soy.jssrc.internal.CanInitOutputVarVisitor;
import com.google.template.soy.jssrc.internal.GenDirectivePluginRequiresVisitor;
import com.google.template.soy.jssrc.internal.GenJsCodeVisitor;
import com.google.template.soy.jssrc.internal.GenJsExprsVisitor.GenJsExprsVisitorFactory;
import com.google.template.soy.jssrc.internal.IsComputableAsJsExprsVisitor;
import com.google.template.soy.jssrc.internal.JsExprTranslator;
import com.google.template.soy.jssrc.internal.JsSrcUtils;
import com.google.template.soy.jssrc.restricted.JsExpr;
import com.google.template.soy.shared.internal.CodeBuilder;
import com.google.template.soy.soytree.CallNode;
import com.google.template.soy.soytree.CallParamContentNode;
import com.google.template.soy.soytree.CallParamNode;
import com.google.template.soy.soytree.ExprUnion;
import com.google.template.soy.soytree.LetContentNode;
import com.google.template.soy.soytree.PrintNode;
import com.google.template.soy.soytree.SoyFileNode;
import com.google.template.soy.soytree.SoyNode;
import com.google.template.soy.soytree.SoyNode.ParentSoyNode;
import com.google.template.soy.soytree.SoyNode.RenderUnitNode;
import com.google.template.soy.soytree.SoyNode.StandaloneNode;
import com.google.template.soy.soytree.TemplateNode;
import com.google.template.soy.types.SoyType;
import com.google.template.soy.types.SoyType.Kind;
import com.google.template.soy.types.SoyTypeOps;

import java.util.List;

/**
 * Generates a series of JavaScript control statements and function calls for rendering one or more
 * templates as HTML. This heavily leverages {@link GenJsCodeVisitor}, adding logic to print the
 * function calls and changing how statements are combined.
 */
public final class GenIncrementalDomCodeVisitor extends GenJsCodeVisitor {

  private static final SoyError PRINT_ATTR_INVALID_KIND = SoyError.of("Cannot have a print "
      + "statement in an attributes list of kind {0}, it must be of kind attributes.");

  @Inject
  GenIncrementalDomCodeVisitor(
      SoyJsSrcOptions jsSrcOptions,
      JsExprTranslator jsExprTranslator,
      IncrementalDomGenCallCodeUtils genCallCodeUtils,
      IsComputableAsJsExprsVisitor isComputableAsJsExprsVisitor,
      CanInitOutputVarVisitor canInitOutputVarVisitor,
      GenJsExprsVisitorFactory genJsExprsVisitorFactory,
      GenDirectivePluginRequiresVisitor genDirectivePluginRequiresVisitor,
      SoyTypeOps typeOps,
      ErrorReporter errorReporter) {
    super(jsSrcOptions,
        true,
        jsExprTranslator,
        genCallCodeUtils,
        isComputableAsJsExprsVisitor,
        canInitOutputVarVisitor,
        genJsExprsVisitorFactory,
        genDirectivePluginRequiresVisitor,
        typeOps,
        errorReporter);
  }

  @Override protected CodeBuilder<JsExpr> createCodeBuilder() {
    return new IncrementalDomCodeBuilder();
  }

  @Override protected boolean requiresGoogModuleDeps() {
    return true;
  }

  @Override protected void addCodeToRequireGeneralDeps(SoyFileNode soyFile) {
    super.addCodeToRequireGeneralDeps(soyFile);
    // Also require incrementaldom so that anything using requires to calculate deps works as
    // expected.
    jsCodeBuilder.appendLine("goog.require('incrementaldom');");
  }

  @Override protected void addCodeToRequireGoogModuleDeps(SoyFileNode soyFile) {
    super.addCodeToRequireGoogModuleDeps(soyFile);
    jsCodeBuilder.appendLine("var IncrementalDom = goog.module.get('incrementaldom');");
  }

  @Override protected void addCodeToAliasFunctions() {
    // The aliased functions that are used by Incremental DOM. We could determine up front which
    // aliased functions are used but they can easily removed by dead code removal as well. The
    // aliasing is done separately from requiring the module so that templates can be generated
    // that aren't built by closure with Incremental DOM provided as a global.
    jsCodeBuilder.appendLine("var ie_open = IncrementalDom.elementOpen;");
    jsCodeBuilder.appendLine("var ie_close = IncrementalDom.elementClose;");
    jsCodeBuilder.appendLine("var ie_void = IncrementalDom.elementVoid;");
    jsCodeBuilder.appendLine("var ie_open_start = IncrementalDom.elementOpenStart;");
    jsCodeBuilder.appendLine("var ie_open_end = IncrementalDom.elementOpenEnd;");
    jsCodeBuilder.appendLine("var itext = IncrementalDom.text;");
    jsCodeBuilder.appendLine("var iattr = IncrementalDom.attr;");
  }

  @Override protected void visitTemplateNode(TemplateNode node) {
    jsCodeBuilder.setContentKind(node.getContentKind());
    super.visitTemplateNode(node);
  }

  @Override protected void generateFunctionBody(TemplateNode node) {
    boolean isTextTemplate = isTextContent(node.getContentKind());
    localVarTranslations.push(Maps.<String, JsExpr>newHashMap());

    // Note: we do not try to combine this into a single return statement if the content is
    // computable as a JsExpr. A JavaScript compiler, such as Closure Compiler, is able to perform
    // the transformation.
    if (isTextTemplate) {
      jsCodeBuilder.appendLine("var output = '';");
      jsCodeBuilder.pushOutputVar("output");
    }

    genParamTypeChecks(node);
    visitChildren(node);

    if (isTextTemplate) {
      jsCodeBuilder.appendLine("return output;");
      jsCodeBuilder.popOutputVar();
    }

    localVarTranslations.pop();
  }

  /**
   * Visits the children of a ParentSoyNode. This function is overridden to not do all of the work
   * that {@link GenJsCodeVisitor} does.
   */
  @Override protected void visitChildren(ParentSoyNode<?> node) {
    for (SoyNode child : node.getChildren()) {
      visit(child);
    }
  }

  /**
   * Generates the content of a {@code let} or {@code param} statement. For HTML and attribute
   * let/param statements, the generated instructions inside the node are wrapped in a function
   * which will be optionally passed to another template and invoked in the correct location. All
   * other kinds of let statements are generated as a simple variable.
   */
  private void visitLetParamContentNode(RenderUnitNode node, String generatedVarName) {
    localVarTranslations.push(Maps.<String, JsExpr>newHashMap());
    jsCodeBuilder.pushOutputVar(generatedVarName);
    jsCodeBuilder.setContentKind(node.getContentKind());

    // The html transform step, performed by HTMLTransformVisitor, ensures that
    // we always have a content kind specified.
    Preconditions.checkState(node.getContentKind() != null);

    switch(node.getContentKind()) {
      case HTML:
      case ATTRIBUTES:
        jsCodeBuilder.appendLine("var " + generatedVarName, " = function() {");
        jsCodeBuilder.increaseIndent();
        visitChildren(node);
        jsCodeBuilder.decreaseIndent();
        jsCodeBuilder.appendLine("};");
        break;
      default:
        jsCodeBuilder.appendLine("var ", generatedVarName, " = '';");
        visitChildren(node);
        break;
    }

    jsCodeBuilder.popOutputVar();
    localVarTranslations.pop();
  }

  /**
   * Generates the content of a {@code let} statement. For HTML and attribute let statements, the
   * generated instructions inside the node are wrapped in a function which will be optionally
   * passed to another template and invoked in the correct location. All other kinds of let/param
   * statements are generated as a simple variable.
   */
  @Override protected void visitLetContentNode(LetContentNode node) {
    String generatedVarName = node.getUniqueVarName();
    visitLetParamContentNode(node, generatedVarName);
    localVarTranslations.peek().put(
        node.getVarName(), new JsExpr(generatedVarName, Integer.MAX_VALUE));
  }

  @Override protected void visitCallParamContentNode(CallParamContentNode node) {
    String generatedVarName = "param" + node.getId();
    visitLetParamContentNode(node, generatedVarName);
  }

  @Override protected void visitCallNode(CallNode node) {
    // If this node has any CallParamContentNode children those contents are not computable as JS
    // expressions, visit them to generate code to define their respective 'param<n>' variables.
    for (CallParamNode child : node.getChildren()) {
      if (child instanceof CallParamContentNode && !isComputableAsJsExprsVisitor.exec(child)) {
        visit(child);
      }
    }

    JsExpr callExpr = genCallCodeUtils.genCallExpr(node, localVarTranslations);

    // If the template is of kind="html" or kind="attributes", we just want to
    // invoke the template call so that it renders the HTML in the current
    // location. For text templates, we always want to concatenate the result
    // to the output variable.
    if (isTextContent(jsCodeBuilder.getContentKind())) {
      jsCodeBuilder.addToOutputVar(ImmutableList.of(callExpr));
    } else {
      jsCodeBuilder.appendLine(callExpr.getText() + ";");
    }
  }

  /**
   * Determines if a given type of content represents text or some sort of HTML.
   * @param contentKind The kind of content to check.
   * @return True if the content represents text, false otherwise.
   */
  private boolean isTextContent(ContentKind contentKind) {
    return contentKind != ContentKind.HTML && contentKind != ContentKind.ATTRIBUTES;
  }

  /**
   * Prints both the static and dynamic attributes for the current node.
   * @param parentNode
   */
  private void printAttributes(ParentNode<HtmlAttributeNode> parentNode) {
    List<HtmlAttributeNode> attributes = parentNode.getChildren();

    // For now, no separating of static and dynamic attributes
    if (!attributes.isEmpty()) {
      jsCodeBuilder.append(", null");
      jsCodeBuilder.increaseIndent();
      jsCodeBuilder.appendLineEnd(",");
      printAttributeList(attributes);
      jsCodeBuilder.decreaseIndent();
    }
  }

  /**
   * Prints a list of attribute values, concatenating the results together
   * @param node The node containing the attribute values
   */
  private void printAttributeValues(HtmlAttributeNode node) {
    List<StandaloneNode> children = node.getChildren();

    if (children.isEmpty()) {
      // No attribute value, e.g. "<button disabled></button>". Need to put an empty string so that
      // the runtime knows to create an attribute.
      jsCodeBuilder.append("''");
    } else {
      visit(children.get(0));

      for (int i = 1; i < children.size(); i++) {
        jsCodeBuilder.append(" + ");
        visit(children.get(i));
      }
    }
  }

  /**
   * Prints one or more attributes as a comma separated list off attribute name, attribute value
   * pairs on their own line. This looks like:
   *
   * <pre>
   *     'attr1', 'value1',
   *     'attr2', 'value2'
   * </pre>
   *
   * @param attributes The attributes to print
   */
  private void printAttributeList(List<HtmlAttributeNode> attributes) {
    HtmlAttributeNode lastAttribute = (attributes.get(attributes.size() - 1));
    jsCodeBuilder.increaseIndent();

    for (HtmlAttributeNode htmlAttributeNode : attributes) {
      jsCodeBuilder.appendLineStart("'", htmlAttributeNode.getName(), "', ");
      printAttributeValues(htmlAttributeNode);

      if (htmlAttributeNode != lastAttribute) {
        jsCodeBuilder.appendLineEnd(",");
      }
    }

    jsCodeBuilder.decreaseIndent();
  }

  /**
   * Visits the {@link HtmlAttributeNode}, this only occurs when we have something like:
   *
   * <pre>
   * &lt;div {if $condition}attr="value"{/if}&gt;
   * </pre>
   *
   * or in a let/param of kind attributes, e.g.
   *
   * <pre>
   * {let $attrs kind="attributes"}
   *   attr="value"
   * {/let}
   * </pre>
   *
   * If no attributes are conditional, then the HtmlAttributeNode will be a child of the
   * corresponding {@link HtmlOpenTagNode}/{@link HtmlVoidTagNode} and will not be visited directly.
   * Note that the value itself could still be conditional in that case.
   *
   * <pre>
   * &lt;div disabled="{if $disabled}true{else}false{/if}"&gt;
   * </pre>
   *
   * This method prints the attribute declaration calls. For example, it would print the call to
   * iattr from the first example, resulting in:
   *
   * <pre>
   * if (condition) {
   *   iattr(attr, "value");
   * }
   * </pre>
   */
  @Override protected void visitHtmlAttributeNode(HtmlAttributeNode node) {
    jsCodeBuilder.appendLineStart("iattr('", node.getName(), "', ");
    printAttributeValues(node);
    jsCodeBuilder.appendLineEnd(");");
  }

  /**
   * Visits an {@link HtmlOpenTagNode}, which occurs when an HTML tag is opened with no conditional
   * attributes. For example:
   * <pre>
   * &lt;div attr="value" attr2="{$someVar}"&gt;...&lt;/div&gt;
   * </pre>
   * generates
   * <pre>
   * ie_open('div', null,
   *     'attr', 'value',
   *     'attr2', someVar);
   * </pre>
   */
  @Override protected void visitHtmlOpenTagNode(HtmlOpenTagNode node) {
    jsCodeBuilder.appendLineStart("ie_open('", node.getTagName(), "', null");
    printAttributes(node);
    jsCodeBuilder.appendLineEnd(");");
    jsCodeBuilder.increaseIndent();
  }

  /**
   * Visits an {@link HtmlCloseTagNode}, which occurs when an HTML tag is closed. For example:
   * <pre>
   * &lt;/div&gt;
   * </pre>
   * generates
   * <pre>
   * ie_close('div');
   * </pre>
   *
   */
  @Override protected void visitHtmlCloseTagNode(HtmlCloseTagNode node) {
    jsCodeBuilder.decreaseIndent();
    jsCodeBuilder.appendLine("ie_close('", node.getTagName(), "');");
  }

  /**
   * Visits an {@link HtmlOpenTagStartNode}, which occurs at the end of an open tag containing
   * children that are not {@link HtmlAttributeNode}s. For example,
   *
   * <pre>
   * &lt;div {$attrs} attr="value"&gt;
   * </pre>
   * The opening bracket and tag translate to
   * <pre>
   * ie_open_start('div');
   * </pre>
   */
  @Override protected void visitHtmlOpenTagStartNode(HtmlOpenTagStartNode node) {
    jsCodeBuilder.appendLine("ie_open_start('", node.getTagName(), "');");
    jsCodeBuilder.increaseIndentTwice();
  }

  /**
   * Visits an {@link HtmlOpenTagEndNode}, which occurs at the end of an open tag containing
   * children that are not {@link HtmlAttributeNode}s. For example,
   *
   * <pre>
   * &lt;div {$attrs} attr="value"&gt;
   * </pre>
   * The closing bracket translates to
   * <pre>
   * ie_open_end();
   * </pre>
   */
  @Override protected void visitHtmlOpenTagEndNode(HtmlOpenTagEndNode node) {
    jsCodeBuilder.decreaseIndentTwice();
    jsCodeBuilder.appendLine("ie_open_end();");
    jsCodeBuilder.increaseIndent();
  }

  /**
   * Visits an {@link HtmlVoidTagNode}, which is equivalent to an {@link HtmlOpenTagNode} followed
   * immediately by an {@link HtmlCloseTagNode}
   *
   * Example:
   * <pre>
   *   &lt;div attr="value" attr2="{$someVar}"&gt;&lt;/div&gt;
   * </pre>
   * generates
   * <pre>
   *   ie_void('div', null,
   *       'attr', 'value',
   *       'attr2', someVar);
   * </pre>
   */
  @Override protected void visitHtmlVoidTagNode(HtmlVoidTagNode node) {
    jsCodeBuilder.appendLineStart("ie_void('", node.getTagName(), "', null");
    printAttributes(node);
    jsCodeBuilder.appendLineEnd(");");
  }

  /**
   * Visits an {@link HtmlTextNode}, which occurs either as a child of any BlockNode or the 'child'
   * of an HTML tag. Note that in the soy tree, tags and their logical HTML children do not have a
   * parent-child relationship, but are rather siblings. For example:
   * <pre>
   * &lt;div&gt;Hello world&lt;/div&gt;
   * </pre>
   * The text "Hello world" translates to
   * <pre>
   * itext('Hello world');
   * </pre>
   */
  @Override protected void visitHtmlTextNode(HtmlTextNode node) {
    jsCodeBuilder.appendLine("itext('", node.getRawText(), "');");
  }

  /**
   * Visits an {@link HtmlPrintNode}, which can occur when a variable is being printed in an
   * attribute declaration or as text.
   * <p>
   * For attributes, if the variable is of kind attributes, it is invoked. Any other kind of
   * variable is an error.
   * </p>
   * <p>
   * For HTML, if the variable is of kind HTML, it is invoked. Any other kind of variable gets
   * wrapped in a call to {@code itext}, resulting in a Text node.
   * </p>
   */
  @Override protected void visitHtmlPrintNode(HtmlPrintNode node) {
    PrintNode printNode = node.getPrintNode();
    ExprUnion exprUnion = printNode.getExprUnion();
    ExprRootNode expr = exprUnion.getExpr();
    List<ExprNode> exprNodes = expr.getChildren();
    ExprNode firstNode = exprNodes.get(0);
    Kind firstKind = firstNode.getType().getKind();

    switch(node.getContext()) {
      case HTML_TAG:
        if (firstKind == SoyType.Kind.ATTRIBUTES) {
          VarRefNode varRefNode = (VarRefNode) firstNode;
          String varName = JsSrcUtils.getVariableName(varRefNode.getName(), localVarTranslations);
          jsCodeBuilder.appendLine(varName, "();");
        } else {
          errorReporter.report(node.getSourceLocation(), PRINT_ATTR_INVALID_KIND, firstKind);
        }
        break;
      case HTML_PCDATA:
        if (firstKind == SoyType.Kind.HTML) {
          VarRefNode varRefNode = (VarRefNode) firstNode;
          String varName = JsSrcUtils.getVariableName(varRefNode.getName(), localVarTranslations);
          jsCodeBuilder.appendLine(varName, "();");
        } else {
          jsCodeBuilder.appendLineStart("itext(");
          for (JsExpr jsExpr : genJsExprsVisitor.exec(printNode)) {
            jsCodeBuilder.append(jsExpr.getText());
          }
          jsCodeBuilder.appendLineEnd(");");
        }
        break;
    }
  }
}
