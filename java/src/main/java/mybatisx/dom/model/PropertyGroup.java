package mybatisx.dom.model;

import mybatisx.dom.converter.ColumnConverter;
import mybatisx.dom.converter.JdbcTypeConverter;
import mybatisx.dom.converter.PropertyConverter;
import mybatisx.dom.converter.TypeHandlerConverter;
import com.intellij.psi.PsiClass;
import com.intellij.psi.xml.XmlAttributeValue;
import com.intellij.util.xml.Attribute;
import com.intellij.util.xml.Convert;
import com.intellij.util.xml.DomElement;
import com.intellij.util.xml.GenericAttributeValue;

/**
 * The interface Property group.
 *
 * @author yanglin
 */
public interface PropertyGroup extends DomElement {

    /**
     * Gets property.
     *
     * @return the property
     */
    @Attribute("property")
    @Convert(PropertyConverter.class)
    GenericAttributeValue<XmlAttributeValue> getProperty();

    /**
     * column
     *
     * @return
     */
    @Attribute("column")
    @Convert(value = ColumnConverter.class, soft = true)
    GenericAttributeValue<XmlAttributeValue> getColumn();

    /**
     * jdbcType
     *
     * @return
     */
    @Attribute("jdbcType")
    @Convert(JdbcTypeConverter.class)
    GenericAttributeValue<XmlAttributeValue> getJdbcType();

    /**
     * jdbcType
     *
     * @return
     */
    @Attribute("typeHandler")
    @Convert(TypeHandlerConverter.class)
    GenericAttributeValue<PsiClass> getTypeHandler();
}
