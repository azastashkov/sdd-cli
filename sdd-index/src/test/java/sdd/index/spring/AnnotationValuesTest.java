package sdd.index.spring;

import com.github.javaparser.StaticJavaParser;
import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.body.ClassOrInterfaceDeclaration;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class AnnotationValuesTest {
    private ClassOrInterfaceDeclaration parse(String annotations) {
        CompilationUnit cu = StaticJavaParser.parse(annotations + "\nclass T {}");
        return cu.getClassByName("T").orElseThrow();
    }

    @Test
    void marker() {
        var t = parse("@Deprecated");
        assertThat(AnnotationValues.annotation(t, "Deprecated")).isPresent();
        assertThat(AnnotationValues.attr(AnnotationValues.annotation(t, "Deprecated").get(), "value")).isEmpty();
    }

    @Test
    void singleMemberIsValue() {
        var t = parse("@RequestMapping(\"/api\")");
        var ann = AnnotationValues.annotation(t, "RequestMapping").get();
        assertThat(AnnotationValues.attr(ann, "value")).isPresent();
        assertThat(AnnotationValues.attr(ann, "path")).isEmpty();
        assertThat(AnnotationValues.attrList(ann, "value")).hasSize(1);
    }

    @Test
    void normalPairsAndArrays() {
        var t = parse("@RequestMapping(path = {\"/a\", \"/b\"}, method = RequestMethod.GET)");
        var ann = AnnotationValues.annotation(t, "RequestMapping").get();
        assertThat(AnnotationValues.attrList(ann, "path")).hasSize(2);
        assertThat(AnnotationValues.attr(ann, "method")).isPresent();
        assertThat(AnnotationValues.attrListAny(ann, "value", "path")).hasSize(2);
    }
}
