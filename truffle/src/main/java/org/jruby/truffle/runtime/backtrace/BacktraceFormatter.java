/*
 * Copyright (c) 2014, 2015 Oracle and/or its affiliates. All rights reserved. This
 * code is released under a tri EPL/GPL/LGPL license. You can use it,
 * redistribute it and/or modify it under the terms of the:
 *
 * Eclipse Public License version 1.0
 * GNU General Public License version 2
 * GNU Lesser General Public License version 2.1
 */
package org.jruby.truffle.runtime.backtrace;

import com.oracle.truffle.api.object.DynamicObject;
import com.oracle.truffle.api.source.NullSourceSection;
import com.oracle.truffle.api.source.SourceSection;
import org.jruby.truffle.nodes.RubyGuards;
import org.jruby.truffle.runtime.RubyArguments;
import org.jruby.truffle.runtime.RubyContext;
import org.jruby.truffle.runtime.control.RaiseException;
import org.jruby.truffle.runtime.layouts.Layouts;
import org.jruby.truffle.runtime.loader.SourceLoader;
import org.jruby.util.cli.Options;

import java.io.PrintWriter;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.EnumSet;
import java.util.List;

public class BacktraceFormatter {

    public enum FormattingFlags {
        OMIT_FROM_PREFIX,
        INCLUDE_CORE_FILES
    }

    private final RubyContext context;
    private final EnumSet<FormattingFlags> flags;

    public static BacktraceFormatter createDefaultFormatter(RubyContext context) {
        final EnumSet<FormattingFlags> flags = EnumSet.noneOf(FormattingFlags.class);

        if (!Options.TRUFFLE_BACKTRACES_HIDE_CORE_FILES.load()) {
            flags.add(FormattingFlags.INCLUDE_CORE_FILES);
        }

        return new BacktraceFormatter(context, flags);
    }

    public BacktraceFormatter(RubyContext context, EnumSet<FormattingFlags> flags) {
        this.context = context;
        this.flags = flags;
    }

    public void printBacktrace(DynamicObject exception, Backtrace backtrace) {
        try (PrintWriter writer = new PrintWriter(System.err)) {
            printBacktrace(exception, backtrace, writer);
        }
    }

    public void printBacktrace(DynamicObject exception, Backtrace backtrace, PrintWriter writer) {
        for (String line : formatBacktrace(exception, backtrace)) {
            writer.println(line);
        }
    }

    public List<String> formatBacktrace(DynamicObject exception, Backtrace backtrace) {
        try {
            final List<Activation> activations = backtrace.getActivations();
            final ArrayList<String> lines = new ArrayList<>();

            lines.add(formatInLine(activations, exception));

            for (int n = 1; n < activations.size(); n++) {
                lines.add(formatFromLine(activations, n));
            }

            return lines;
        } catch (Exception e) {
            return Arrays.asList(String.format("(exception while constructing backtrace: %s %s)", e.getMessage(), e.getStackTrace()[0].toString()));
        }
    }

    private String formatInLine(List<Activation> activations, DynamicObject exception) {
        final StringBuilder builder = new StringBuilder();

        final Activation activation = activations.get(0);
        final SourceSection sourceSection = activation.getCallNode().getEncapsulatingSourceSection();
        final SourceSection reportedSourceSection;
        final String reportedName;

        if (isCore(sourceSection) && !flags.contains(FormattingFlags.INCLUDE_CORE_FILES)) {
            reportedSourceSection = nextUserSourceSection(activations, 1);
            reportedName = RubyArguments.getMethod(activation.getMaterializedFrame().getArguments()).getName();
        } else {
            reportedSourceSection = sourceSection;
            reportedName = reportedSourceSection.getIdentifier();
        }

        if (reportedSourceSection == null || reportedSourceSection.getSource() == null) {
            builder.append("???");
        } else {
            builder.append(reportedSourceSection.getSource().getName());
            builder.append(":");
            builder.append(reportedSourceSection.getStartLine());
            builder.append(":in `");
            builder.append(reportedName);
            builder.append("'");
        }

        if (exception != null) {
            String message;
            try {
                Object messageObject = context.send(exception, "message", null);
                if (RubyGuards.isRubyString(messageObject)) {
                    message = messageObject.toString();
                } else {
                    message = Layouts.EXCEPTION.getMessage(exception).toString();
                }
            } catch (RaiseException e) {
                message = Layouts.EXCEPTION.getMessage(exception).toString();
            }

            builder.append(": ");
            builder.append(message);
            builder.append(" (");
            builder.append(Layouts.MODULE.getFields(Layouts.BASIC_OBJECT.getLogicalClass(exception)).getName());
            builder.append(")");
        }

        return builder.toString();
    }

    private String formatFromLine(List<Activation> activations, int n) {
        final String formattedLine = formatLine(activations, n);

        if (flags.contains(FormattingFlags.OMIT_FROM_PREFIX)) {
            return formattedLine;
        } else {
            return "\tfrom " + formattedLine;
        }
    }

    public String formatLine(List<Activation> activations, int n) {
        final Activation activation = activations.get(n);
        final SourceSection sourceSection = activation.getCallNode().getEncapsulatingSourceSection();
        final SourceSection reportedSourceSection;
        String reportedName;

        if (isCore(sourceSection) && !flags.contains(FormattingFlags.INCLUDE_CORE_FILES)) {
            reportedSourceSection = nextUserSourceSection(activations, n);

            try {
                reportedName = RubyArguments.getMethod(activation.getMaterializedFrame().getArguments()).getName();
            } catch (Exception e) {
                reportedName = "???";
            }
        } else {
            reportedSourceSection = sourceSection;
            reportedName = sourceSection.getIdentifier();
        }

        final StringBuilder builder = new StringBuilder();

        if (reportedSourceSection instanceof NullSourceSection) {
            builder.append(reportedSourceSection.getShortDescription());
        } else if (reportedSourceSection == null) {
            builder.append("???");
        } else {
            builder.append(reportedSourceSection.getSource().getName());
            builder.append(":");
            builder.append(reportedSourceSection.getStartLine());
        }

        builder.append(":in `");
        builder.append(reportedName);
        builder.append("'");

        return builder.toString();
    }

    private SourceSection nextUserSourceSection(List<Activation> activations, int n) {
        while (n < activations.size()) {
            SourceSection sourceSection = activations.get(n).getCallNode().getEncapsulatingSourceSection();

            if (!isCore(sourceSection)) {
                return sourceSection;
            }

            n++;
        }
        return null;
    }

    private boolean isCore(SourceSection sourceSection) {
        return sourceSection instanceof NullSourceSection || sourceSection.getSource().getPath().startsWith(SourceLoader.TRUFFLE_SCHEME);
    }

}
