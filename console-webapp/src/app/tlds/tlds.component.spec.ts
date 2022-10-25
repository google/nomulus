import { ComponentFixture, TestBed } from '@angular/core/testing';

import { TldsComponent } from './tlds.component';

describe('TldsComponent', () => {
  let component: TldsComponent;
  let fixture: ComponentFixture<TldsComponent>;

  beforeEach(async () => {
    await TestBed.configureTestingModule({
      declarations: [ TldsComponent ]
    })
    .compileComponents();

    fixture = TestBed.createComponent(TldsComponent);
    component = fixture.componentInstance;
    fixture.detectChanges();
  });

  it('should create', () => {
    expect(component).toBeTruthy();
  });
});
