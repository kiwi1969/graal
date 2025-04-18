/*
 * Copyright (c) 2013, 2021, Oracle and/or its affiliates. All rights reserved.
 * DO NOT ALTER OR REMOVE COPYRIGHT NOTICES OR THIS FILE HEADER.
 *
 * This code is free software; you can redistribute it and/or modify it
 * under the terms of the GNU General Public License version 2 only, as
 * published by the Free Software Foundation.  Oracle designates this
 * particular file as subject to the "Classpath" exception as provided
 * by Oracle in the LICENSE file that accompanied this code.
 *
 * This code is distributed in the hope that it will be useful, but WITHOUT
 * ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or
 * FITNESS FOR A PARTICULAR PURPOSE.  See the GNU General Public License
 * version 2 for more details (a copy is included in the LICENSE file that
 * accompanied this code).
 *
 * You should have received a copy of the GNU General Public License version
 * 2 along with this work; if not, write to the Free Software Foundation,
 * Inc., 51 Franklin St, Fifth Floor, Boston, MA 02110-1301 USA.
 *
 * Please contact Oracle, 500 Oracle Parkway, Redwood Shores, CA 94065 USA
 * or visit www.oracle.com if you need additional information or have any
 * questions.
 */
package jdk.graal.compiler.options;

import java.util.List;

/**
 * Describes the attributes of a static field {@linkplain Option option} and provides access to its
 * {@linkplain OptionKey value}.
 */
public final class OptionDescriptor {

    private final String name;
    private final OptionType optionType;
    private final Class<?> optionValueType;
    private final String help;
    private final List<String> extraHelp;
    private final OptionKey<?> optionKey;
    private final OptionsContainer container;
    private final String fieldName;
    private final OptionStability stability;
    private final boolean deprecated;
    private final String deprecationMessage;

    private static final String[] NO_EXTRA_HELP = {};

    public static OptionDescriptor create(String name,
                    OptionType optionType,
                    Class<?> optionValueType,
                    String help,
                    Object container,
                    String fieldName,
                    OptionKey<?> option) {
        return create(name, optionType, optionValueType, help, NO_EXTRA_HELP, container, fieldName, option, OptionStability.EXPERIMENTAL, false, "");
    }

    public static OptionDescriptor create(String name,
                    OptionType optionType,
                    Class<?> optionValueType,
                    String help,
                    Object container,
                    String fieldName,
                    OptionKey<?> option,
                    OptionStability stability,
                    boolean deprecated,
                    String deprecationMessage) {
        return create(name, optionType, optionValueType, help, NO_EXTRA_HELP, container, fieldName, option, stability, deprecated, deprecationMessage);
    }

    public static OptionDescriptor create(String name,
                    OptionType optionType,
                    Class<?> optionValueType,
                    String help,
                    String[] extraHelp,
                    Object container,
                    String fieldName,
                    OptionKey<?> option,
                    OptionStability stability,
                    boolean deprecated,
                    String deprecationMessage) {
        OptionsContainer oc = OptionsContainer.asContainer(container);
        Class<?> declaringClass = oc.getDeclaringClass();
        assert option != null : declaringClass + "." + fieldName;
        OptionDescriptor result = option.getDescriptor();
        if (result == null) {
            List<String> extraHelpList = extraHelp == null ? List.of() : List.of(extraHelp);
            result = new OptionDescriptor(name, optionType, optionValueType, help, extraHelpList, oc, fieldName, option, stability, deprecated, deprecationMessage);
            option.setDescriptor(result);
        }
        assert result.name.equals(name) : result.name + " != " + name;
        assert result.optionValueType == optionValueType : result.optionValueType + " != " + optionValueType;
        assert result.getDeclaringClass() == declaringClass : result.getDeclaringClass() + " != " + declaringClass;
        assert result.fieldName.equals(fieldName) : result.fieldName + " != " + fieldName;
        assert result.optionKey == option : result.optionKey + " != " + option;
        return result;
    }

    private OptionDescriptor(String name,
                    OptionType optionType,
                    Class<?> optionValueType,
                    String help,
                    List<String> extraHelp,
                    OptionsContainer container,
                    String fieldName,
                    OptionKey<?> optionKey,
                    OptionStability stability,
                    boolean deprecated,
                    String deprecationMessage) {
        this.name = name;
        this.optionType = optionType;
        this.optionValueType = optionValueType;
        this.help = help;
        this.extraHelp = extraHelp;
        this.optionKey = optionKey;
        this.container = container;
        this.fieldName = fieldName;
        this.stability = stability;
        this.deprecated = deprecated || deprecationMessage != null && !deprecationMessage.isEmpty();
        this.deprecationMessage = deprecationMessage;
        assert !optionValueType.isPrimitive() : "must use boxed optionValueType instead of " + optionValueType;
    }

    /**
     * Gets the type of values stored in the option. This will be the boxed type for a primitive
     * option.
     */
    public Class<?> getOptionValueType() {
        return optionValueType;
    }

    /**
     * Gets a descriptive help message for the option. This message should be self-contained without
     * relying on {@link #getExtraHelp() extra help lines}.
     *
     * @see Option#help()
     */
    public String getHelp() {
        return help;
    }

    /**
     * Gets extra lines of help text. These lines should not be subject to any line wrapping or
     * formatting apart from indentation.
     */
    public List<String> getExtraHelp() {
        return extraHelp;
    }

    /**
     * Gets the name of the option. It's up to the client of this object how to use the name to get
     * a user specified value for the option from the environment.
     */
    public String getName() {
        return container.prefixed(name);
    }

    /**
     * Gets the type of the option.
     */
    public OptionType getOptionType() {
        return optionType;
    }

    /**
     * Gets the boxed option value.
     */
    public OptionKey<?> getOptionKey() {
        return optionKey;
    }

    /**
     * Gets metadata about the class declaring the option.
     */
    public OptionsContainer getContainer() {
        return container;
    }

    /**
     * Gets the class declaring the option.
     */
    public Class<?> getDeclaringClass() {
        return container.getDeclaringClass();
    }

    public String getFieldName() {
        return fieldName;
    }

    /**
     * Gets a description of the location where this option is stored.
     */
    public String getLocation() {
        return getDeclaringClass().getName() + "." + getFieldName();
    }

    /**
     * Returns the stability of this option.
     */
    public OptionStability getStability() {
        return stability;
    }

    /**
     * Returns {@code true} if the option is deprecated.
     */
    public boolean isDeprecated() {
        return deprecated;
    }

    /**
     * Returns the deprecation reason and the recommended replacement.
     */
    public String getDeprecationMessage() {
        return deprecationMessage;
    }
}
