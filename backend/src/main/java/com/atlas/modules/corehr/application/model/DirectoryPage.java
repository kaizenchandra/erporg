package com.atlas.modules.corehr.application.model;

import com.atlas.modules.corehr.domain.model.Employee;
import java.util.List;

public record DirectoryPage(
    List<Employee> items, long total, int page, int size, List<String> departments) {}
