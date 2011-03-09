package org.owasp.html;

import java.util.Iterator;
import java.util.List;
import java.util.regex.Pattern;

import com.google.common.annotations.VisibleForTesting;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.Lists;

/**
 * An HTML sanitizer policy that tries to preserve simple CSS by converting it
 * to {@code <font>} tags which allow fewer ways to embed JavaScript.
 */
class StylingPolicy extends ElementAndAttributePolicyBasedSanitizerPolicy {
  StylingPolicy(
      HtmlStreamEventReceiver out,
      ImmutableMap<String, ElementAndAttributePolicies> elAndAttrPolicies) {
    super(out, elAndAttrPolicies);
  }

  @Override public void openTag(String elementName, List<String> attrs) {
    // Parts of the superclass method are repeated here, so if you change this,
    // be sure to check the super-class.
    String style = null;
    for (Iterator<String> it = attrs.iterator(); it.hasNext();) {
      String name = it.next();
      if ("style".equals(name)) {
        style = it.next();
        break;
      } else {
        it.next();
      }
    }
    ElementAndAttributePolicies policies = elAndAttrPolicies.get(elementName);
    String adjustedElementName = applyPolicies(elementName, attrs, policies);
    if (adjustedElementName != null) {
      List<String> fontAttributes = null;
      if (style != null) {
        fontAttributes = cssPropertiesToFontAttributes(style);
        if (fontAttributes.isEmpty()) {
          fontAttributes = null;
        }
      }
      // If we have something to output, emit it.
      if (!(attrs.isEmpty() && policies.skipIfEmpty
            && fontAttributes == null)) {
        skipText = false;
        writeOpenTag(policies, adjustedElementName, attrs);
        if (fontAttributes != null) {
          synthesizeOpenTag("font", fontAttributes);
          // Rely on the tag balancer to close it.
        }
        return;
      }
    }
    deferOpenTag(elementName);
  }

  /** Used to track CSS property names while processing CSS. */
  private enum CssPropertyType {
    FONT,
    FACE,
    SIZE,
    COLOR,
    DIR,
    ALIGN,
    WEIGHT,
    STYLE,
    NONE
    ;
  }

  private static final Pattern ALLOWED_CSS_SIZE = Pattern.compile(
      "medium|(?:small|large)r|(?:xx?-)(?:small|large)|[0-9]+(pt|%)");

  private static final Pattern ALLOWED_CSS_WEIGHT = Pattern.compile(
      "normal|bold(?:er)?|lighter|[1-9]00");

  private static final Pattern ALLOWED_CSS_STYLE = Pattern.compile(
      "italic|oblique|normal");

  private static final ImmutableMap<String, CssPropertyType>
      BY_CSS_PROPERTY_NAME = ImmutableMap.<String, CssPropertyType>builder()
      .put("font", CssPropertyType.FONT)
      .put("font-family", CssPropertyType.FACE)
      .put("font-size", CssPropertyType.SIZE)
      .put("color", CssPropertyType.COLOR)
      .put("text-align", CssPropertyType.ALIGN)
      .put("direction", CssPropertyType.DIR)
      .put("font-weight", CssPropertyType.WEIGHT)
      .put("font-style", CssPropertyType.STYLE)
      .build();

  /**
   * Lossy conversion from CSS properties into the attributes of a
   * <code>&lt;font&gt;</code> tag that allows textual styling that affects
   * layout, but does not allow breaking out of a clipping region, absolute
   * positioning, image loading, tab index changes, or code execution.
   *
   * @return A list of alternating attribute names and values.
   */
  @VisibleForTesting
  static List<String> cssPropertiesToFontAttributes(String style) {

    // We walk over CSS tokens to extract salient bits.
    class StyleExtractor implements CssGrammar.PropertyHandler {
      CssPropertyType type = CssPropertyType.NONE;

      // Values that are not-whitelisted are put into font attributes to render
      // the innocuous.
      StringBuilder face, color;
      String align, dir;
      // These values are white-listed so we know they can't affect anything
      // other than font-face appearance, and layout.
      String cssSize, cssWeight, cssFontStyle;

      public void url(String token) {
        // Ignore.
      }
      public void startProperty(String propertyName) {
        CssPropertyType type = BY_CSS_PROPERTY_NAME.get(propertyName);
        this.type = type != null ? type : CssPropertyType.NONE;
      }
      public void quotedString(String token) {
        switch (type) {
          case FONT: case FACE:
            if (face == null) { face = new StringBuilder(); }
            face.append(' ').append(CssGrammar.cssContent(token));
            break;
          default: break;
        }
      }

      public void quantity(String token) {
        switch (type) {
          case FONT:
          case SIZE:
            token = Strings.toLowerCase(token);
            if (ALLOWED_CSS_SIZE.matcher(token).matches()) {
              cssSize = token;
            }
            break;
          case FACE:
            if (face == null) { face = new StringBuilder(); }
            face.append(' ').append(token);
            break;
          case COLOR:
            if (color == null) { color = new StringBuilder(); }
            color.append(' ').append(token);
            break;
          case WEIGHT:
            if (ALLOWED_CSS_WEIGHT.matcher(token).matches()) {
              cssWeight = token;
            }
            break;
          default: break;
        }
      }

      public void identifierOrHash(String token) {
        switch (type) {
          case SIZE:
            token = Strings.toLowerCase(token);
            if (ALLOWED_CSS_SIZE.matcher(token).matches()) {
              cssSize = token;
            }
            break;
          case WEIGHT:
            token = Strings.toLowerCase(token);
            if (ALLOWED_CSS_WEIGHT.matcher(token).matches()) {
              cssWeight = token;
            }
            break;
          case FACE:
            if (face == null) { face = new StringBuilder(); }
            face.append(' ').append(token);
            break;
          case FONT:
            token = Strings.toLowerCase(token);
            if (ALLOWED_CSS_WEIGHT.matcher(token).matches()) {
              cssWeight = token;
            } else if (ALLOWED_CSS_SIZE.matcher(token).matches()) {
              cssSize = token;
            } else if (ALLOWED_CSS_STYLE.matcher(token).matches()) {
              cssFontStyle = token;
            } else {
              if (face == null) { face = new StringBuilder(); }
              face.append(' ').append(token);
            }
            break;
          case COLOR:
            if (color == null) { color = new StringBuilder(); }
            if (token.length() == 4 && token.charAt(0) == '#') {
              // #ABC -> #AABBCC
              color.append('#').append(token.charAt(1)).append(token.charAt(1))
                   .append(token.charAt(2)).append(token.charAt(2))
                   .append(token.charAt(3)).append(token.charAt(3));
            } else {
              color.append(token);
            }
            break;
          case STYLE:
            token = Strings.toLowerCase(token);
            if (ALLOWED_CSS_STYLE.matcher(token).matches()) {
              cssFontStyle = token;
            }
            break;
          case ALIGN:
            align = token;
            break;
          case DIR:
            dir = token;
            break;
          default: break;
        }
      }

      public void punctuation(String token) {
        switch (type) {
          case FACE: case FONT:
            // Commas separate font-families since HTML fonts fall-back to
            // simpler forms based on the installed font-set.
            if (",".equals(token) && face != null) { face.append(','); }
            break;
          case COLOR:
            // Parentheses and commas in the rgb(...) color form.
            if (color != null) { color.append(token); }
            break;
          default: break;
        }
      }

      public void endProperty() {
        type = CssPropertyType.NONE;
      }

      @TCB
      List<String> toFontAttributes() {
        List<String> fontAttributes = Lists.newArrayList();
        if (face != null) {
          fontAttributes.add("face");
          fontAttributes.add(face.toString().trim());
        }
        if (color != null) {
          fontAttributes.add("color");
          fontAttributes.add(color.toString().trim());
        }
        if (align != null) {
          fontAttributes.add("align");
          fontAttributes.add(align);
        }
        if (dir != null) {
          fontAttributes.add("dir");
          fontAttributes.add(dir);
        }
        ImmutableList<String> styleParts;
        {
          ImmutableList.Builder<String> b = ImmutableList.builder();
          if (cssWeight != null) {
            b.add("font-weight").add(cssWeight);
          }
          if (cssSize != null) {
            b.add("font-size").add(cssSize);
          }
          if (cssFontStyle != null) {
            b.add("font-style").add(cssFontStyle);
          }
          styleParts = b.build();
        }
        if (!styleParts.isEmpty()) {
          StringBuilder cssProperties = new StringBuilder();
          boolean isPropertyName = true;
          for (String stylePart : styleParts) {
            cssProperties.append(stylePart).append(isPropertyName ? ':' : ';');
            isPropertyName = !isPropertyName;
          }
          fontAttributes.add("style");
          fontAttributes.add(cssProperties.toString());
        }

        return fontAttributes;
      }
    }


    StyleExtractor extractor = new StyleExtractor();
    CssGrammar.asPropertyGroup(style, extractor);
    return extractor.toFontAttributes();
  }
}