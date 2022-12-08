import {NgModule} from '@angular/core';
import {MatCardModule} from '@angular/material/card';
import {MatTableModule} from '@angular/material/table';

const MATERIAL_MODULES = [
  MatCardModule,
  MatTableModule,
];

@NgModule({imports: MATERIAL_MODULES, exports: MATERIAL_MODULES})
export class MaterialModule {
}
